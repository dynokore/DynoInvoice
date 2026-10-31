package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.GrantsController
import com.dynokore.gst.access.adapters.http.IdpGroupController
import com.dynokore.gst.access.adapters.separation.VariationPointConflictingPairs
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.idp.DirectoryGroupMembers
import com.dynokore.gst.access.application.idp.IdpGroupMappings
import com.dynokore.gst.access.application.idp.SyncIdpGroupsUseCase
import com.dynokore.gst.access.application.separation.ConflictingRolePairs
import com.dynokore.gst.access.contract.model.GrantRequest
import com.dynokore.gst.access.contract.model.IdpGroupMappingRequest
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.productruntime.catalogue.GstVariationPointCatalogue
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.PolicyCommandServlet
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.http.HttpServlet
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The environment of the VP-82 tests of INV-T-9276: a started service context, one tenant with a first `tenant.admin`
 * (token-a) and a `security.officer` (token-s), a second tenant with its own `tenant.admin` (token-o), and VP-82 set
 * through the mounted PolicyCommandServlet. The pinned catalogue still lists VP-82 as `planned` and a planned row
 * cannot be set, so the context answers the catalogue through [AvailableVp82].
 */
internal class PairsEnv(
    val context: AssertableWebApplicationContext,
    val tenant: TenantId,
    val other: TenantId,
) {
    val mapper = ObjectMapper()
    val tenants = context.getBean(TenantContext::class.java)
    val tx = context.getBean(TransactionRunner::class.java)
    val grants = context.getBean(GrantsController::class.java)
    val idp = context.getBean(IdpGroupController::class.java)
    val sync = context.getBean(SyncIdpGroupsUseCase::class.java)
    val repository = context.getBean(GrantRepository::class.java)
    val mappings = context.getBean(IdpGroupMappings::class.java)
    val captured = context.getBean(CapturedEvents::class.java)

    init {
        Seeds.grant(context, tenant, SeedSpec(ADMIN, Role.TENANT_ADMIN))
        Seeds.grant(context, other, SeedSpec(OTHER_ADMIN, Role.TENANT_ADMIN))
        listOf(tenant, other).forEach {
            context
                .getBean(StructureReplica::class.java)
                .accept(
                    OrganisationStructureChanged(it, "ENT-1", StructureChange.CREATED, BoundaryFixture.entityNode()),
                )
        }
    }

    fun seedSecurityOfficer() = Seeds.grant(context, tenant, SeedSpec(OFFICER, Role.SECURITY_OFFICER))

    fun seed(
        identity: String,
        role: Role,
        status: GrantStatus = GrantStatus.ACTIVE,
    ) = Seeds.grant(context, tenant, SeedSpec(identity, role, status = status))

    fun <T> asAdmin(block: () -> T): T = tenants.bind(tenant, ADMIN) { block() }

    fun grant(
        identity: String,
        role: String,
    ) = asAdmin {
        grants.grantRole(
            UUID.randomUUID(),
            GrantRequest(
                GrantRequest.GranteeKind.STAFF,
                identity,
                role,
                "tenant:$tenant",
                LocalDate.now().minusDays(1),
                LocalDate.now().plusDays(400),
            ),
        )
    }

    fun map(
        group: String,
        role: String,
    ) = asAdmin {
        idp.mapIdpGroup(UUID.randomUUID(), IdpGroupMappingRequest(group, role, "entity:ENT-1", null, null))
    }

    fun synchronise() = asAdmin { sync.run(ADMIN, TenantRef(tenant.toString())) }

    fun rowsOf(identity: String): List<Grant> =
        asAdmin { tx.inTransaction { repository.findByIdentity(IdentityRef(identity)) } }

    /** Every grant row of [identities] as the repository returns them, compared by equality. */
    fun rows(vararg identities: String): List<String> =
        identities
            .flatMap(::rowsOf)
            .map { g ->
                listOf(g.ref.value, g.identity.value, g.role.wire, g.scope, g.window, g.status, g.version, g.source)
                    .joinToString("|")
            }.sorted()

    fun mappingCount() = asAdmin { tx.inTransaction { mappings.listActive() } }.size

    fun authorityChanges() = captured.envelopes.filter { it.type == PlatformEventCatalogue.AUTHORITY_CHANGED.eventType }

    fun storedRows(): Int =
        BoundaryFixture.asSuperuser { connection ->
            connection
                .prepareStatement("select count(*) from access.config_setting where tenant_id = ? and vp_key = 'VP-82'")
                .use { statement ->
                    statement.setObject(1, tenant.value)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
        }

    fun auditData(id: String): List<JsonNode> =
        BoundaryFixture.asSuperuser { connection ->
            connection
                .prepareStatement("select data::text from access.outbox where id = ? and tenant_id = ?")
                .use { statement ->
                    statement.setString(1, id)
                    statement.setObject(2, tenant.value)
                    statement.executeQuery().use { rows ->
                        generateSequence { if (rows.next()) mapper.readTree(rows.getString(1)) else null }.toList()
                    }
                }
        }

    fun refusal(block: () -> Any?): ApiRefusal {
        val thrown = catchThrowable { block() }
        assertThat(thrown).isInstanceOf(ApiRefusal::class.java)
        return thrown as ApiRefusal
    }

    fun assertRefused(
        refusal: ApiRefusal,
        detail: String,
    ) {
        assertThat(refusal.code).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
        assertThat(refusal.detail).isEqualTo(detail)
    }

    fun granted(
        identity: String,
        role: String,
    ) {
        assertThat(grant(identity, role).statusCode.value()).isEqualTo(201)
    }

    // ---- the setting path: the mounted servlet ------------------------------------------------------------------

    private fun servlet(): HttpServlet =
        context
            .getBeansOfType(ServletRegistrationBean::class.java)
            .values
            .single { it.urlMappings.contains(PolicyCommandServlet.MAPPING) }
            .servlet as HttpServlet

    fun today(): LocalDate = LocalDate.ofInstant(context.getBean(Clock::class.java).instant(), SINGAPORE)

    fun post(
        path: String,
        token: String,
        body: String,
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest("POST", path)
        request.contentType = "application/json"
        request.addHeader("Authorization", "Bearer $token")
        request.addHeader("Idempotency-Key", UUID.randomUUID().toString())
        request.setContent(body.toByteArray())
        return MockHttpServletResponse().also { servlet().service(request, it) }
    }

    /** `setPolicy` of VP-82 by [token] for [scope]: CONFLICTING_PAIRS with [pairs] (`first`/`second` wire names). */
    fun setPairs(
        vararg pairs: Pair<String, String>,
        token: String = "token-a",
        scope: TenantId = tenant,
        reason: String = "tenant scenario",
    ): MockHttpServletResponse {
        val list = pairs.joinToString(",", "[", "]") { """{"first":"${it.first}","second":"${it.second}"}""" }
        return post(
            "/internal/v1/policies/VP-82",
            token,
            """{"scopeLevel":"TENANT","scopeRef":"$scope",""" +
                """"selected":[{"option":"CONFLICTING_PAIRS","value":$list}],""" +
                """"effectiveDate":"${today()}","reason":"$reason"}""",
        )
    }

    fun setNoExtraPairs(token: String = "token-a"): MockHttpServletResponse =
        post(
            "/internal/v1/policies/VP-82",
            token,
            """{"scopeLevel":"TENANT","scopeRef":"$tenant","selected":[{"option":"NO_EXTRA_PAIRS"}],""" +
                """"effectiveDate":"${today()}","reason":"back to the default"}""",
        )

    fun store(vararg pairs: Pair<String, String>): JsonNode {
        val response = setPairs(*pairs)
        assertThat(response.status).describedAs(response.contentAsString).isBetween(200, 299)
        return mapper.readTree(response.contentAsString)
    }

    companion object {
        const val ADMIN = "user:tenant-admin"
        const val OFFICER = "user:security-officer"
        const val OTHER_ADMIN = "user:other-admin"
        val SINGAPORE: ZoneId = ZoneId.of("Asia/Singapore")

        /** Runs [block] in a started context with the double [AvailableVp82] when [withDouble], own tenants per run. */
        fun run(
            withDouble: Boolean = true,
            directory: DirectoryGroupMembers = MovableDirectory(),
            block: (PairsEnv) -> Unit,
        ) {
            val tenant = TenantId(UUID.randomUUID())
            val other = TenantId(UUID.randomUUID())
            BoundaryFixture
                .runner(catalogueMatrix = true, form = "SAAS")
                .withBean(DirectoryGroupMembers::class.java, { directory })
                .withBean(
                    EndUserPrincipalResolver::class.java,
                    {
                        TestTokenPrincipals(
                            mapOf(
                                "token-a" to EndUserPrincipal(ADMIN, tenant, emptySet()),
                                "token-s" to EndUserPrincipal(OFFICER, tenant, emptySet()),
                                "token-o" to EndUserPrincipal(OTHER_ADMIN, other, emptySet()),
                            ),
                        )
                    },
                ).let {
                    if (withDouble) {
                        it.withBean(
                            VariationPointCatalogue::class.java,
                            { AvailableVp82(GstVariationPointCatalogue("access-svc")) },
                        )
                    } else {
                        it
                    }
                }.run { context ->
                    context.startupFailure?.let { throw it }
                    block(PairsEnv(context, tenant, other))
                }
        }
    }
}

/**
 * INV-AC-9272 and INV-AC-9273 (INV-T-9276, INV-REQ-9271): VP-82 applied by `grantRole`, `mapIdpGroup` and the directory
 * synchronisation in a started service context over a real PostgreSQL.
 */
class ConflictingPairsGrantTest {
    private val pairDetail = "these two roles cannot be held by the same person in this tenant"
    private val ackRequired = "security.officer and tenant.admin need the recorded sole-operator acknowledgement"
    private val operatorDetail = "an operator role cannot be granted in a customer tenant"
    private val operatorWires =
        setOf(
            "platform.support",
            "platform.engineer",
            "release.manager",
            "commercial.admin",
            "regulatory.author",
            "regulatory.approver",
        )
    private val a = "tax.operator"
    private val b = "tax.approver"

    private fun run(
        directory: MovableDirectory = MovableDirectory(),
        block: (PairsEnv) -> Unit,
    ) = PairsEnv.run(directory = directory, block = block)

    /** The outcome of one grantRole call as the table states it: the status, or the refusal detail. */
    private fun PairsEnv.outcome(
        identity: String,
        role: String,
    ): String {
        val thrown = catchThrowable { grant(identity, role) }
        return when {
            thrown == null -> "201"
            thrown is ApiRefusal -> "${thrown.code}:${thrown.detail}"
            else -> throw thrown
        }
    }

    /** The table of main: a repeat of the held role is accepted, the built-in pair needs the acknowledgement. */
    private fun expected(
        held: Role,
        granted: String,
        catalogue: RoleCatalogue,
    ): String? =
        when {
            granted in operatorWires && catalogue.roles.any { it.wire == granted } -> {
                "${ClosedErrorCode.ACCESS_DENIED}:$operatorDetail"
            }

            granted in operatorWires -> {
                null
            }

            catalogue.roles.none { it.wire == granted } -> {
                "${ClosedErrorCode.CONTRACT_VIOLATION}:role is not in the role catalogue"
            }

            held.wire == granted -> {
                "201"
            }

            setOf(held, Role.ofWire(granted)) == setOf(Role.SECURITY_OFFICER, Role.TENANT_ADMIN) -> {
                "${ClosedErrorCode.ACCESS_DENIED}:$ackRequired"
            }

            else -> {
                "201"
            }
        }

    @Test
    fun `INV-AC-9272 with no value stored every held role and granted role has the outcome of main`() =
        run { env ->
            val catalogue = env.context.getBean(RoleCatalogue::class.java)
            val wires = Role.entries.map { it.wire }
            assertThat(wires).hasSize(19)
            Role.entries.forEachIndexed { index, held ->
                wires.forEach { granted ->
                    val want = expected(held, granted, catalogue) ?: return@forEach
                    // a fresh holder per call: an accepted grant would otherwise change the next call's held set
                    val holder = "IDN-H$index-${wires.indexOf(granted)}"
                    env.seed(holder, held)
                    val message = "held ${held.wire} granted $granted"
                    assertThat(env.outcome(holder, granted)).describedAs(message).isEqualTo(want)
                }
            }
            assertThat(env.storedRows()).isZero()
        }

    @Test
    fun `INV-AC-9272 with no value stored mapIdpGroup of each role provisions a holder of any other role`() {
        val directory = MovableDirectory()
        run(directory) { env ->
            val catalogue = env.context.getBean(RoleCatalogue::class.java)
            val grantable = Role.entries.filter { r -> catalogue.roles.any { it.wire == r.wire } }
            grantable.filter { it.wire !in operatorWires }.forEach { role ->
                if (role == Role.SECURITY_OFFICER || role == Role.TENANT_ADMIN) return@forEach
                val member = "IDN-M-${role.wire}"
                env.seed(member, Role.SECURITY_OFFICER)
                directory.set("group-${role.wire}", member)
                assertThat(env.map("group-${role.wire}", role.wire).statusCode.value()).isEqualTo(201)
                assertThat(env.rowsOf(member).map { it.role }).describedAs(role.wire).contains(role)
            }
        }
    }

    @Test
    fun `INV-AC-9273 a listed pair is refused in both orders with the detail and writes no grant and no event`() =
        run { env ->
            env.store(a to b)
            env.seed("IDN-A", Role.TAX_OPERATOR)
            env.seed("IDN-B", Role.TAX_APPROVER)
            val before = env.rows("IDN-A", "IDN-B")
            env.captured.envelopes.clear()
            env.assertRefused(env.refusal { env.grant("IDN-A", b) }, pairDetail)
            env.assertRefused(env.refusal { env.grant("IDN-B", a) }, pairDetail)
            assertThat(env.rows("IDN-A", "IDN-B")).isEqualTo(before)
            assertThat(env.authorityChanges()).isEmpty()
        }

    @Test
    fun `INV-AC-9273 neither role, an unlisted role and another tenant pair are accepted`() =
        run { env ->
            env.store(a to b)
            env.granted("IDN-N", a)
            env.seed("IDN-C", Role.TAX_OPERATOR)
            env.granted("IDN-C", "finance.viewer")
            // the pair is listed for the other tenant only
            val otherResponse = env.setPairs("auditor" to "finance.viewer", token = "token-o", scope = env.other)
            assertThat(otherResponse.status).describedAs(otherResponse.contentAsString).isBetween(200, 299)
            env.seed("IDN-D", Role.AUDITOR)
            env.granted("IDN-D", "finance.viewer")
        }

    @Test
    fun `INV-AC-9273 a revoked grant does not count as held`() =
        run { env ->
            env.store(a to b)
            env.seed("IDN-R", Role.TAX_OPERATOR, GrantStatus.REVOKED)
            env.granted("IDN-R", b)
        }

    @Test
    fun `INV-AC-9273 the built-in pair still needs the acknowledgement under CONFLICTING_PAIRS`() =
        run { env ->
            env.store(a to b)
            env.seed("IDN-P", Role.TENANT_ADMIN)
            env.assertRefused(env.refusal { env.grant("IDN-P", "security.officer") }, ackRequired)
            assertThat(env.rowsOf("IDN-P").map { it.role }).containsExactly(Role.TENANT_ADMIN)
        }

    @Test
    fun `INV-AC-9273 a listed pair that contains a built-in role is refused with the pair detail`() =
        run { env ->
            env.store("auditor" to "finance.viewer", "tax.operator" to "security.officer")
            env.seed("IDN-S", Role.SECURITY_OFFICER)
            env.assertRefused(env.refusal { env.grant("IDN-S", a) }, pairDetail)
        }

    @Test
    fun `INV-AC-9273 mapIdpGroup stores the mapping, skips the member completing the pair and provisions the other`() {
        val directory = MovableDirectory()
        run(directory) { env ->
            env.store(a to b)
            env.seed("IDN-A", Role.TAX_OPERATOR)
            directory.set("acme-approvers", "IDN-A", "IDN-X")
            val before = env.rows("IDN-A")
            assertThat(env.map("acme-approvers", b).statusCode.value()).isEqualTo(201)
            assertThat(env.mappingCount()).isEqualTo(1)
            assertThat(env.rows("IDN-A")).isEqualTo(before)
            assertThat(env.rowsOf("IDN-X").map { it.role }).containsExactly(Role.TAX_APPROVER)
            // the directory synchronisation is held to the same rule: a later member holding the other role
            env.seed("IDN-Y", Role.TAX_OPERATOR)
            directory.set("acme-approvers", "IDN-A", "IDN-X", "IDN-Y")
            env.synchronise()
            assertThat(env.rowsOf("IDN-Y").map { it.role }).containsExactly(Role.TAX_OPERATOR)
            assertThat(env.rowsOf("IDN-X").map { it.role }).containsExactly(Role.TAX_APPROVER)
        }
    }

    @Test
    fun `INV-AC-9273 setting the value changes no row and a person holding both keeps both and takes others`() =
        run { env ->
            env.seed("IDN-Both", Role.TAX_OPERATOR)
            env.seed("IDN-Both", Role.TAX_APPROVER)
            val before = env.rows("IDN-Both")
            env.store(a to b)
            assertThat(env.rows("IDN-Both")).isEqualTo(before)
            env.granted("IDN-Both", "finance.viewer")
            assertThat(env.rowsOf("IDN-Both").map { it.role })
                .containsExactlyInAnyOrder(Role.TAX_OPERATOR, Role.TAX_APPROVER, Role.FINANCE_VIEWER)
            assertThat(env.rows("IDN-Both")).containsAll(before)
        }

    @Test
    fun `INV-AC-9273 setting NO_EXTRA_PAIRS again lifts the refusal for later grants`() =
        run { env ->
            env.store(a to b)
            env.seed("IDN-A", Role.TAX_OPERATOR)
            env.assertRefused(env.refusal { env.grant("IDN-A", b) }, pairDetail)
            val response = env.setNoExtraPairs()
            assertThat(response.status).describedAs(response.contentAsString).isBetween(200, 299)
            env.granted("IDN-A", b)
        }

    @Test
    fun `INV-AC-9273 the bean of the started context is the VP-82 reader`() =
        run { env ->
            assertThat(env.context.getBean(ConflictingRolePairs::class.java)::class)
                .isEqualTo(VariationPointConflictingPairs::class)
        }
}
