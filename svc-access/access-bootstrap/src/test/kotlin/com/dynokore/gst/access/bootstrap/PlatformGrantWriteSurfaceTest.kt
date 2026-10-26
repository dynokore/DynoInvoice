package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.GrantsController
import com.dynokore.gst.access.adapters.http.IdpGroupController
import com.dynokore.gst.access.contract.model.GrantRequest
import com.dynokore.gst.access.contract.model.IdpGroupMappingRequest
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-9279, the part "no access operation creates or revokes an operator-role grant" (INV-REQ-862 behavior 4): the
 * write operations of the vendored access contract are compared by equality with a fixed table that says which of them
 * can write `access.role_grant`; every writer is then called under the platform tenant for each of the six
 * operator-role wire names and must give the refusal main gives, with the platform rows unchanged.
 */
class PlatformGrantWriteSurfaceTest {
    private val platformId = TenantId.PLATFORM.toString()

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "$path is not on the classpath" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private val openApi = resource("gst/contracts/access/openapi.yaml")
    private val asyncApi = resource("gst/contracts/access/asyncapi.yaml")

    /** The operationId of every POST, PUT, PATCH and DELETE operation of the contract. */
    private fun writeOperations(openApi: String): Set<String> =
        OperationsGate
            .contractOf(openApi, asyncApi)
            .operations
            .filterValues { it.method in WRITE_METHODS }
            .keys

    /** The differences between the write operations of a contract and the table, by equality of the operationId. */
    private fun tableFindings(
        operations: Set<String>,
        table: Map<String, Boolean> = WRITE_TABLE,
    ): List<String> =
        (operations - table.keys).map { "write operation $it is missing from the table" } +
            (table.keys - operations).map { "table row $it is not a write operation of the contract" }

    @Test
    fun `the write operations of the contract equal the table and the table names the writers of role_grant`() {
        assertThat(tableFindings(writeOperations(openApi))).isEmpty()
        assertThat(WRITE_TABLE.filterValues { it }.keys)
            .containsExactlyInAnyOrder("grantRole", "revokeGrant", "mapIdpGroup", "unmapIdpGroup")
    }

    @Test
    fun `probe - an extra POST operation seedPlatformGrant in a copy of the contract fails the table comparison`() {
        val extended =
            openApi.replace(
                "paths:\n",
                "paths:\n  /internal/v1/access/probe-seed:\n    post:\n      operationId: seedPlatformGrant\n",
            )
        assertThat(tableFindings(writeOperations(extended)))
            .containsExactly("write operation seedPlatformGrant is missing from the table")
    }

    @Test
    fun `probe - a deleted table row and a look-alike operationId fail the table comparison`() {
        val operations = writeOperations(openApi)
        assertThat(tableFindings(operations, WRITE_TABLE - "grantRole"))
            .containsExactly("write operation grantRole is missing from the table")
        val lookAlike = WRITE_TABLE - "grantRole" + ("GrantRole" to true)
        assertThat(tableFindings(operations, lookAlike))
            .containsExactlyInAnyOrder(
                "write operation grantRole is missing from the table",
                "table row GrantRole is not a write operation of the contract",
            )
    }

    /** The rows of the platform tenant, all columns, in a stable order; the superuser sees every tenant's rows. */
    private fun platformRows(connection: Connection): List<String> =
        connection
            .prepareStatement(
                "select g::text from access.role_grant g where g.tenant_id = ?::uuid order by g.id",
            ).use { statement ->
                statement.setString(1, platformId)
                statement.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                }
            }

    /** One ACTIVE operator grant of the platform tenant that stays a stranger to the tests that count rows. */
    private fun insertPlatformGrant(
        connection: Connection,
        identity: String,
        grantRef: String,
    ) {
        connection.createStatement().use {
            it.execute(
                "insert into access.identity (id, tenant_id, identity_ref, grantee_kind, created_at, " +
                    "created_by, updated_at, updated_by, version) values (gen_random_uuid(), '$platformId', " +
                    "'$identity', 'STAFF', now(), 'test', now(), 'test', 0)",
            )
            it.execute(
                "insert into access.role_grant (id, tenant_id, grant_ref, identity_ref, role, scope_level, " +
                    "scope_ref, valid_from, status, grant_source, created_at, created_by, updated_at, updated_by, " +
                    "version) values (gen_random_uuid(), '$platformId', '$grantRef', '$identity', " +
                    "'platform.support', 'TENANT', '$platformId', now() - interval '1 day', 'ACTIVE', 'BY_HAND', " +
                    "now(), 'test', now(), 'test', 0)",
            )
        }
    }

    private fun deletePlatformGrant(
        connection: Connection,
        identity: String,
    ) {
        connection.createStatement().use {
            listOf("role_grant", "identity").forEach { table ->
                it.execute("delete from access.$table where tenant_id = '$platformId' and identity_ref = '$identity'")
            }
        }
    }

    private fun refusalCode(block: () -> Any?): ClosedErrorCode? =
        (runCatching { block() }.exceptionOrNull() as? ApiRefusal)?.code

    @Test
    fun `INV-AC-9279 each writer of role_grant is refused under the platform tenant and no row changes`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val identity = "IDN-PLAT-$suffix"
        val grantRef = "GRT-P$suffix"
        BoundaryFixture.asSuperuser { connection ->
            connection.autoCommit = true
            insertPlatformGrant(connection, identity, grantRef)
            try {
                BoundaryFixture.runner().run { context ->
                    context.startupFailure?.let { throw it }
                    val platform = context.getBean(PlatformTenantBinding::class.java)
                    val grants = context.getBean(GrantsController::class.java)
                    val idp = context.getBean(IdpGroupController::class.java)
                    val before = platformRows(connection)
                    assertThat(before).hasSize(1)
                    val from = LocalDate.now().minusDays(1)
                    val to = LocalDate.now().plusDays(10)
                    val calls = mutableListOf<Pair<String, () -> Any?>>()
                    OPERATOR_ROLES.forEach { role ->
                        calls +=
                            "grantRole $role" to {
                                grants.grantRole(
                                    UUID.randomUUID(),
                                    GrantRequest(
                                        GrantRequest.GranteeKind.STAFF,
                                        "IDN-NEW-$suffix",
                                        role,
                                        "tenant:$platformId",
                                        from,
                                        to,
                                    ),
                                )
                            }
                        calls +=
                            "mapIdpGroup $role" to {
                                idp.mapIdpGroup(
                                    UUID.randomUUID(),
                                    IdpGroupMappingRequest("grp-$suffix", role, "tenant:$platformId", from, to),
                                )
                            }
                    }
                    calls += "revokeGrant" to { grants.revokeGrant(grantRef, UUID.randomUUID()) }
                    calls += "unmapIdpGroup" to { idp.unmapIdpGroup("MAP-$suffix", UUID.randomUUID()) }
                    // the actor holds no grant, so the refusal is the caller check's: 403 ACCESS_DENIED
                    calls.forEach { (name, call) ->
                        val refusal = platform.bindPlatform("user:operator-$suffix") { refusalCode(call) }
                        assertThat(refusal).describedAs(name).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
                        assertThat(platformRows(connection)).describedAs(name).isEqualTo(before)
                    }
                }
            } finally {
                deletePlatformGrant(connection, identity)
            }
        }
    }

    private companion object {
        val WRITE_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")

        val OPERATOR_ROLES =
            listOf(
                "platform.support",
                "platform.engineer",
                "release.manager",
                "commercial.admin",
                "regulatory.author",
                "regulatory.approver",
            )

        /** Every write operation of the contract: true when its use case can insert or update `access.role_grant`. */
        val WRITE_TABLE: Map<String, Boolean> =
            mapOf(
                "grantRole" to true, // GrantRoleUseCase.insert
                "revokeGrant" to true, // RevokeGrantUseCase: grants.update to REVOKED
                "mapIdpGroup" to true, // GroupGrantReconciler.add / revoke
                "unmapIdpGroup" to true, // UnmapIdpGroup revokes the grants made from the mapping
                // no other use case of main calls GrantRepository.insert or update (checked by grep on main); the
                // provision* and accept* operations are stubs today and a task that makes them write a grant must set true
                "createPrincipal" to false,
                "authenticatePrincipal" to false,
                "issueAccessToken" to false,
                "rotatePrincipal" to false,
                "revokePrincipal" to false,
                "withdrawCredential" to false,
                "recordSoleOperator" to false,
                "withdrawSoleOperatorAcknowledgement" to false,
                "requestElevation" to false,
                "decideElevation" to false,
                "withdrawElevationRequest" to false,
                "openOperatorSession" to false,
                "inviteDiagnostics" to false,
                "withdrawDiagnosticInvitation" to false,
                "issueSignatoryLink" to false,
                "verifySignatoryLink" to false,
                "provisionFirstAdministrator" to false,
                "provisionInstallationFirstAdministrator" to false,
                "reissueFirstAdministratorInvitation" to false,
                "acceptFirstAdministratorInvitation" to false,
                "unlockBreakGlass" to false,
                "resealBreakGlass" to false,
                "signInBreakGlass" to false,
                "recordBreakGlassAction" to false,
                "runAccessReview" to false,
                "closeAccessReview" to false,
                "grantConsent" to false,
                "withdrawConsent" to false,
                "acknowledgeMapping" to false,
                "chooseLandingScreen" to false,
                "clearLandingChoice" to false,
            )
    }
}
