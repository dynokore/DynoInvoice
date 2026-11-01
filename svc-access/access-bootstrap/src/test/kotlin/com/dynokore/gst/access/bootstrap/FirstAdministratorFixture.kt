package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.invitation.HmacInvitationCodes
import com.dynokore.gst.access.bootstrap.served.ContractDocument
import com.dynokore.gst.access.bootstrap.served.ContractOperation
import com.dynokore.gst.access.bootstrap.served.ServedContext
import com.dynokore.gst.access.bootstrap.served.ServedDoubles
import com.dynokore.gst.access.bootstrap.served.subjectOf
import com.dynokore.gst.access.bootstrap.served.workloadToken
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** The two operations under test, the services that call them and the helpers that read the stored rows. */
object FirstAdministratorFixture {
    const val FIRST = "provisionFirstAdministrator"
    const val INSTALLATION = "provisionInstallationFirstAdministrator"
    const val CONTROL_PLANE = "control-plane-svc"
    const val INSTALLER = "installer"

    // a service name has no digit (WorkloadIdentityProperties.SERVICE_NAME), so `control-plane-svc-2` cannot be mapped
    const val LOOK_ALIKE = "control-plane-svc-alt"
    const val OTHER = "tenancy-svc"
    val CALLERS = listOf(CONTROL_PLANE, INSTALLER, LOOK_ALIKE, OTHER)
    val JSON: JsonMapper = JsonMapper.builder().build()
    const val EMAIL = "first.admin@example.sg"
    const val EXPIRES = "2030-01-05T08:00:00Z"

    /** The tables whose every column is searched for the plaintext code. */
    val TABLES =
        listOf(
            "access.first_administrator_invitation",
            "access.role_grant",
            "access.identity",
            "access.idempotency_key",
        )

    private val operations: List<ContractOperation> by lazy { ContractDocument.read() }

    fun operation(id: String): ContractOperation = operations.single { it.operationId == id }

    fun newTenant(): TenantId = TenantId(UUID.randomUUID())

    fun body(
        tenantRef: String,
        email: String = EMAIL,
        expires: String = EXPIRES,
    ): String = """{"tenantRef":"$tenantRef","administratorEmail":"$email","invitationExpiresAt":"$expires"}"""

    /** One call of [operation] by the service [caller] (null: no workload token) with the end-user token [auth]. */
    fun ServedContext.provision(
        operation: String,
        caller: String?,
        tenantRef: String,
        key: UUID = UUID.randomUUID(),
        auth: String? = com.dynokore.gst.access.bootstrap.served.OPERATOR_TOKEN,
    ): MockHttpServletResponse = provisionBody(operation, caller, body(tenantRef), key, auth)

    /** The call of the control-plane service with the operator token (a tenantless operator), as the plane makes it. */
    fun ServedContext.first(
        tenant: TenantId,
        key: UUID = UUID.randomUUID(),
        auth: String? = com.dynokore.gst.access.bootstrap.served.OPERATOR_TOKEN,
    ): MockHttpServletResponse = provision(FIRST, CONTROL_PLANE, tenant.toString(), key, auth)

    /** The same call with a request body of the test's own. */
    fun ServedContext.provisionBody(
        operation: String,
        caller: String?,
        body: String,
        key: UUID = UUID.randomUUID(),
        auth: String? = com.dynokore.gst.access.bootstrap.served.OPERATOR_TOKEN,
    ): MockHttpServletResponse {
        val headers = mutableMapOf<String, String>()
        caller?.let { headers["X-Workload-Token"] = workloadToken(it) }
        auth?.let { headers["Authorization"] = it }
        return post(operation(operation), headers, body, key)
    }

    fun json(response: MockHttpServletResponse): JsonNode = JSON.readTree(response.contentAsString)

    fun json(text: String): JsonNode = JSON.readTree(text)

    /** Every row of [table] of [tenant], one JSON text per row, ordered; read as the superuser. */
    fun rows(
        table: String,
        tenant: TenantId,
    ): List<String> =
        BoundaryFixture.asSuperuser { c ->
            c.prepareStatement("select to_jsonb(t)::text from $table t where tenant_id = ?::uuid order by 1").use {
                it.setString(1, tenant.toString())
                it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }

    /** The rows of the four tables of [tenant], by table. */
    fun snapshot(tenant: TenantId): Map<String, List<String>> = TABLES.associateWith { rows(it, tenant) }

    fun adminGrants(tenant: TenantId): List<String> =
        rows("access.role_grant", tenant).filter { it.contains("\"role\": \"tenant.admin\"") }

    /** Inserts an identity and a `tenant.admin` grant row of [tenant] straight into the tables (superuser). */
    fun seedAdminRow(
        tenant: TenantId,
        status: String,
        source: String = "BY_HAND",
        scope: Pair<String, String> = "TENANT" to tenant.toString(),
    ) {
        val identity = "IDN-" + UUID.randomUUID().toString().take(8)
        val (scopeLevel, scopeRef) = scope
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "insert into access.identity (id, tenant_id, identity_ref, grantee_kind, created_at, created_by, " +
                        "updated_at, updated_by, version) " +
                        "values (?::uuid, ?::uuid, ?, 'STAFF', now(), 'seed', now(), 'seed', 0)",
                ).use {
                    it.setString(1, UUID.randomUUID().toString())
                    it.setString(2, tenant.toString())
                    it.setString(3, identity)
                    it.executeUpdate()
                }
            c
                .prepareStatement(
                    "insert into access.role_grant (id, tenant_id, grant_ref, identity_ref, role, scope_level, " +
                        "scope_ref, " +
                        "valid_from, status, grant_source, created_at, created_by, updated_at, updated_by, " +
                        "version) " +
                        "values (?::uuid, ?::uuid, ?, ?, 'tenant.admin', ?, ?, now() - interval '1 day', ?, ?, " +
                        "now(), 'seed', now(), 'seed', 0)",
                ).use {
                    it.setString(1, UUID.randomUUID().toString())
                    it.setString(2, tenant.toString())
                    it.setString(3, "GRT-" + UUID.randomUUID().toString().take(8))
                    it.setString(4, identity)
                    it.setString(5, scopeLevel)
                    it.setString(6, scopeRef)
                    it.setString(7, status)
                    it.setString(8, source)
                    it.executeUpdate()
                }
        }
    }

    /** The code the service must derive for [ref] under the test key of [version] (the formula of INV-Q-14261 A). */
    fun expectedCode(
        tenant: TenantId,
        ref: String,
        key: String = TestSecrets.INVITATION_KEY,
    ): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val text = "access-invitation-code:v1:$tenant:$ref"
        return java.util.Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(mac.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    /** A mounted directory of the fixture with the invitation key files given by [keys] (version to key text). */
    fun mount(keys: Map<String, String>): Path {
        val root = BoundaryFixture.mount(TestSecrets.newSigningKey())
        Files.deleteIfExists(root.resolve("access/invitation-code-key/${TestSecrets.INVITATION_KEY_VERSION}"))
        keys.forEach { (version, key) ->
            val file = root.resolve("access/invitation-code-key/$version")
            Files.createDirectories(file.parent)
            Files.writeString(file, key)
        }
        return root
    }

    /**
     * Runs [block] over the application context with the callers mapped to `subject-of-<service>`, the key version
     * [version] and the mounted directory [mounted].
     */
    fun withService(
        mounted: Path = mount(mapOf(TestSecrets.INVITATION_KEY_VERSION to TestSecrets.INVITATION_KEY)),
        version: String = TestSecrets.INVITATION_KEY_VERSION,
        callers: List<String> = CALLERS,
        block: (ServedContext) -> Unit,
    ) {
        val properties =
            callers.sorted().flatMapIndexed { i, service ->
                listOf(
                    "service-runtime.workload-identity.callers[$i].service=$service",
                    "service-runtime.workload-identity.callers[$i].subject=${subjectOf(service)}",
                )
            } + "access.invitation.code-key-version=$version"
        BoundaryFixture
            .runner(mounted = mounted)
            .withUserConfiguration(ServedDoubles::class.java)
            .withPropertyValues(*properties.toTypedArray())
            .run { context ->
                context.startupFailure?.let { throw it }
                com.dynokore.gst.access.bootstrap.served.Doubles
                    .reset()
                block(ServedContext(context))
            }
    }

    fun codes(served: ServedContext): HmacInvitationCodes =
        served.application.getBean(com.dynokore.gst.access.application.invitation.InvitationCodes::class.java)
            as HmacInvitationCodes
}
