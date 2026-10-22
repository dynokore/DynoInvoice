package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.GrantsController
import com.dynokore.gst.access.adapters.http.SoleOperatorController
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.separation.SoleOperatorAcks
import com.dynokore.gst.access.contract.model.GrantRequest
import com.dynokore.gst.access.contract.model.SoleOperatorAckRequest
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.configresolver.VpStatus
import com.dynokore.platform.productruntime.catalogue.GstVariationPointCatalogue
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.PolicyCommandServlet
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.http.HttpServlet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * INV-AC-8289 (INV-T-8292, INV-REQ-8287): the three values of VP-81 set through the mounted PolicyCommandServlet by a
 * test-only principal source, and applied by `grantRole` and `withdrawSoleOperatorAcknowledgement`. The pinned
 * catalogue still lists VP-81 as `planned` (INV-T-8293 makes it available), and a planned row cannot be set; so these
 * tests answer the catalogue through [AvailableVp81]: the pinned catalogue with VP-81 alone raised to AVAILABLE.
 */
class SoleOperatorScopeValuesTest {
    private val mapper = ObjectMapper()

    private val d1 =
        "only one person may hold security.officer and tenant.admin under the sole-operator acknowledgement"
    private val d2 = "a person still holds security.officer and tenant.admin; revoke one of the roles first"
    private val d3 =
        "a second person must hold security.officer or tenant.admin before the acknowledgement is withdrawn"
    private val ackRequired = "security.officer and tenant.admin need the recorded sole-operator acknowledgement"

    private val singapore = ZoneId.of("Asia/Singapore")

    /** A is the first tenant.admin and, where the case needs a holder of both roles, X. */
    private val actorA = "user:tenant-admin"
    private val actorS = "user:security-officer"

    private inner class Env(
        val context: AssertableWebApplicationContext,
        val tenant: TenantId,
    ) {
        val tenants = context.getBean(TenantContext::class.java)
        val tx = context.getBean(TransactionRunner::class.java)
        val grants = context.getBean(GrantsController::class.java)
        val soleOperator = context.getBean(SoleOperatorController::class.java)
        val acks = context.getBean(SoleOperatorAcks::class.java)
        val repository = context.getBean(GrantRepository::class.java)

        init {
            Seeds.grant(context, tenant, SeedSpec(actorA, Role.TENANT_ADMIN))
        }

        /** S: an actor whose only grant is a Tenant-scope security.officer. */
        fun seedSecurityOfficer() = Seeds.grant(context, tenant, SeedSpec(actorS, Role.SECURITY_OFFICER))

        fun <T> asAdmin(block: () -> T): T = tenants.bind(tenant, actorA) { block() }

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

        fun record(): String =
            asAdmin {
                soleOperator
                    .recordSoleOperator(UUID.randomUUID(), SoleOperatorAckRequest(tenant.toString(), "external audit"))
                    .body!!
                    .ackRef
            }

        fun withdraw(ackRef: String) =
            asAdmin { soleOperator.withdrawSoleOperatorAcknowledgement(ackRef, UUID.randomUUID()) }

        fun activeAck() = asAdmin { tx.inTransaction { acks.findActive() } }

        fun holders(role: Role): Set<String> =
            asAdmin { tx.inTransaction { acks.holders(role).map { it.value }.toSet() } }

        /** Every grant row of [identities], as the repository returns them: compared by equality. */
        fun rows(vararg identities: String): List<String> =
            asAdmin {
                tx.inTransaction { identities.flatMap { repository.findByIdentity(IdentityRef(it)) } }
            }.map(::describe).sorted()

        private fun describe(g: Grant) =
            listOf(g.ref.value, g.identity.value, g.role.wire, g.scope, g.window, g.status, g.version, g.source)
                .joinToString("|")

        fun refusal(block: () -> Any?): ApiRefusal {
            val thrown = runCatching { block() }.exceptionOrNull()
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
        ) = assertThat(grant(identity, role).statusCode.value()).isEqualTo(201)

        // ---- the setting path: the mounted servlet --------------------------------------------------------------

        private fun servlet(): HttpServlet {
            val registrations = context.getBeansOfType(ServletRegistrationBean::class.java).values
            return registrations.single { it.urlMappings.contains(PolicyCommandServlet.MAPPING) }.servlet as HttpServlet
        }

        fun today(): LocalDate = LocalDate.ofInstant(context.getBean(Clock::class.java).instant(), singapore)

        fun advanceDays(days: Long) =
            (context.getBean(Clock::class.java) as MovableClock).advance(Duration.ofDays(days))

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
            val response = MockHttpServletResponse()
            servlet().service(request, response)
            return response
        }

        private fun body(
            option: String,
            effective: LocalDate,
            extra: String,
        ) = """{"scopeLevel":"TENANT","scopeRef":"$tenant","selected":[{"option":"$option"}],""" +
            """"effectiveDate":"$effective","reason":"tenant scenario"$extra}"""

        fun set(
            option: String,
            token: String = "token-a",
            relaxation: String? = null,
            effective: LocalDate = today(),
            reason: String = "tenant scenario",
        ): MockHttpServletResponse {
            val extra = relaxation?.let { ""","relaxationId":"$it"""" }.orEmpty()
            return post(
                "/internal/v1/policies/VP-81",
                token,
                body(option, effective, extra).replace("\"tenant scenario\"", "\"$reason\""),
            )
        }

        fun acknowledge(
            option: String,
            token: String = "token-a",
            effective: LocalDate = today(),
        ): String {
            val response =
                post(
                    "/internal/v1/policies/VP-81/relaxations",
                    token,
                    body(option, effective, ""","asRole":"tenant.admin""""),
                )
            assertThat(response.status).describedAs(response.contentAsString).isBetween(200, 299)
            return mapper.readTree(response.contentAsString).get("relaxationId").asString()
        }

        /** The two calls of a looser value: the acknowledgement of `tenant.admin`, then the value with its id. */
        fun setAcknowledged(
            option: String,
            effective: LocalDate = today(),
            reason: String = "tenant scenario",
        ): JsonNode {
            val relaxation = acknowledge(option, effective = effective)
            val response = set(option, relaxation = relaxation, effective = effective, reason = reason)
            assertThat(response.status).describedAs(response.contentAsString).isBetween(200, 299)
            return mapper.readTree(response.contentAsString)
        }
    }

    private fun run(block: (Env) -> Unit) {
        // each run has its own tenant: the database outlives a test, and policies and grants are stored per tenant
        val tenant = TenantId(UUID.randomUUID())
        BoundaryFixture
            .runner(catalogueMatrix = true, form = "SAAS")
            .withBean(EndUserPrincipalResolver::class.java, { principals(tenant) })
            .withBean(VariationPointCatalogue::class.java, { AvailableVp81(GstVariationPointCatalogue("access-svc")) })
            .run { context ->
                context.startupFailure?.let { throw it }
                block(Env(context, tenant))
            }
    }

    private fun principals(tenant: TenantId): EndUserPrincipalResolver =
        TestTokenPrincipals(
            mapOf(
                "token-a" to EndUserPrincipal(actorA, tenant, emptySet()),
                "token-s" to EndUserPrincipal(actorS, tenant, emptySet()),
            ),
        )

    /** The pinned catalogue with VP-81 alone answered as available; every other row is the pinned row. */
    private class AvailableVp81(
        private val pinned: GstVariationPointCatalogue,
    ) : VariationPointCatalogue {
        override fun find(key: VariationPointKey) =
            pinned.find(key)?.let { if (key.value == "VP-81") it.copy(status = VpStatus.AVAILABLE) else it }

        override fun keys() = pinned.keys()
    }

    /** X holds both roles under an active acknowledgement; returns the acknowledgement. */
    private fun Env.soleOperator(): String {
        grant(actorA, "tenant.admin")
        val ack = record()
        grant(actorA, "security.officer")
        return ack
    }

    @Test
    fun `INV-AC-8289 S1 ONE_HOLDER keeps one holder of both and lets the acknowledgement go with a second person`() =
        run { env ->
            val ack = env.soleOperator()
            env.setAcknowledged("ONE_HOLDER")
            env.granted("IDN-Y", "tenant.admin")
            env.assertRefused(env.refusal { env.grant("IDN-Y", "security.officer") }, d1)
            env.granted("IDN-Z", "security.officer")
            val before = env.rows(actorA, "IDN-Y", "IDN-Z")
            assertThat(env.withdraw(ack).statusCode.value()).isEqualTo(204)
            assertThat(env.activeAck()).isNull()
            assertThat(env.rows(actorA, "IDN-Y", "IDN-Z")).isEqualTo(before)
            assertThat(env.holders(Role.SECURITY_OFFICER)).contains(actorA)
            assertThat(env.holders(Role.TENANT_ADMIN)).contains(actorA)
        }

    @Test
    fun `INV-AC-8289 S2 ONE_HOLDER refuses the withdrawal while only one person holds any of the two roles`() =
        run { env ->
            env.soleOperator()
            val ack = env.activeAck()!!.ref
            env.setAcknowledged("ONE_HOLDER")
            assertThat(env.holders(Role.SECURITY_OFFICER)).containsOnly(actorA)
            assertThat(env.holders(Role.TENANT_ADMIN)).containsOnly(actorA)
            env.assertRefused(env.refusal { env.withdraw(ack) }, d3)
            assertThat(env.activeAck()?.ref).isEqualTo(ack)
        }

    @Test
    fun `INV-AC-8289 S3 ANY_NUMBER grants a second holder and withdraws at any time with no grant changed`() =
        run { env ->
            val ack = env.soleOperator()
            env.setAcknowledged("ANY_NUMBER")
            env.granted("IDN-Y", "tenant.admin")
            env.granted("IDN-Y", "security.officer")
            val before = env.rows(actorA, "IDN-Y")
            assertThat(env.withdraw(ack).statusCode.value()).isEqualTo(204)
            assertThat(env.activeAck()).isNull()
            assertThat(env.rows(actorA, "IDN-Y")).isEqualTo(before)
        }

    @Test
    fun `INV-AC-8289 S3 ANY_NUMBER withdraws although nobody else holds either role`() =
        run { env ->
            env.setAcknowledged("ANY_NUMBER")
            val ack = env.soleOperator()
            assertThat(env.withdraw(ack).statusCode.value()).isEqualTo(204)
        }

    @Test
    fun `INV-AC-8289 S4 with nothing set the default value applies`() =
        run { env ->
            val ack = env.soleOperator()
            env.granted("IDN-Y", "tenant.admin")
            env.assertRefused(env.refusal { env.grant("IDN-Y", "security.officer") }, d1)
            env.assertRefused(env.refusal { env.withdraw(ack) }, d2)
            assertThat(env.activeAck()?.ref).isEqualTo(ack)
        }

    @Test
    fun `INV-AC-8289 S5 a looser value without the acknowledgement is refused and the value is unchanged`() =
        run { env ->
            env.soleOperator()
            val refused = env.set("ANY_NUMBER")
            assertThat(refused.status).isEqualTo(409)
            assertThat(refused.contentAsString).contains("POL-018")
            env.granted("IDN-Y", "tenant.admin")
            env.assertRefused(env.refusal { env.grant("IDN-Y", "security.officer") }, d1)
            env.setAcknowledged("ANY_NUMBER")
            env.granted("IDN-Y", "security.officer")
        }

    @Test
    fun `INV-AC-8289 S6 a tenant-scope security officer cannot set a value and the value is unchanged`() =
        run { env ->
            env.soleOperator()
            env.seedSecurityOfficer()
            val refused = env.set("ANY_NUMBER", token = "token-s")
            assertThat(refused.status).isEqualTo(403)
            assertThat(refused.contentAsString).contains("POL-004")
            val acknowledged =
                env.post(
                    "/internal/v1/policies/VP-81/relaxations",
                    "token-s",
                    """{"scopeLevel":"TENANT","scopeRef":"${env.tenant}","selected":[{"option":"ANY_NUMBER"}],""" +
                        """"effectiveDate":"${env.today()}","asRole":"tenant.admin"}""",
                )
            assertThat(acknowledged.status).isGreaterThanOrEqualTo(400)
            env.granted("IDN-Y", "tenant.admin")
            env.assertRefused(env.refusal { env.grant("IDN-Y", "security.officer") }, d1)
        }

    @Test
    fun `INV-AC-8289 S7 a value applies from its effective date and the audit holds prior value and reason`() =
        run { env ->
            val ack = env.soleOperator()
            env.granted("IDN-Z", "security.officer")
            val tomorrow = env.today().plusDays(1)
            val first = env.setAcknowledged("ONE_HOLDER", effective = tomorrow, reason = "first reason")
            assertThat(first.get("prior").isNull).isTrue()
            // before the effective date the default applies: A holds both, so the withdrawal is refused with D2
            env.assertRefused(env.refusal { env.withdraw(ack) }, d2)
            env.advanceDays(1)
            // from it ONE_HOLDER applies: the withdrawal is allowed and A keeps both grants
            val before = env.rows(actorA)
            assertThat(env.withdraw(ack).statusCode.value()).isEqualTo(204)
            assertThat(env.rows(actorA)).isEqualTo(before)
            val second = env.setAcknowledged("ANY_NUMBER", reason = "second reason")
            assertThat(
                second
                    .get("prior")
                    .get("selected")
                    .get(0)
                    .get("option")
                    .asString(),
            ).isEqualTo("ONE_HOLDER")
            assertThat(second.get("reason").asString()).isEqualTo("second reason")
            assertThat(second.get("auditEventId").asString()).isNotBlank()
        }

    @Test
    fun `INV-AC-8289 S8 changing ANY_NUMBER to the default revokes and flags no grant and blocks the next ones`() =
        run { env ->
            val ack = env.soleOperator()
            env.setAcknowledged("ANY_NUMBER")
            env.granted("IDN-Y", "tenant.admin")
            env.granted("IDN-Y", "security.officer")
            val before = env.rows(actorA, "IDN-Y")
            val back = env.set("ONE_HOLDER_WITHDRAW_BLOCKED")
            assertThat(back.status).describedAs(back.contentAsString).isBetween(200, 299)
            assertThat(env.rows(actorA, "IDN-Y")).isEqualTo(before)
            env.granted("IDN-Z", "tenant.admin")
            env.assertRefused(env.refusal { env.grant("IDN-Z", "security.officer") }, d1)
            env.assertRefused(env.refusal { env.withdraw(ack) }, d2)
            assertThat(env.rows(actorA, "IDN-Y")).isEqualTo(before)
        }

    @Test
    fun `INV-AC-8289 S9 a stored option key the service does not know fails the request and writes nothing`() =
        run { env ->
            val ack = env.soleOperator()
            env.granted("IDN-Y", "tenant.admin")
            val before = env.rows(actorA, "IDN-Y")
            BoundaryFixture.asSuperuser { connection ->
                connection
                    .prepareStatement(
                        "insert into access.config_setting (id, tenant_id, vp_key, scope_level, scope_id," +
                            " option_keys, source, effective_from, set_by, reason, recorded_at)" +
                            " values (?, ?, 'VP-81', 'TENANT', ?, ARRAY['ONE_HOLDERS'], 'OVERRIDE'," +
                            " current_date - 1, 'user:tenant-admin', 'fixture', now())",
                    ).use {
                        it.setObject(1, UUID.randomUUID())
                        it.setObject(2, env.tenant.value)
                        it.setString(3, env.tenant.toString())
                        it.executeUpdate()
                    }
            }
            val grantFailure = runCatching { env.grant("IDN-Y", "security.officer") }.exceptionOrNull()
            assertThat(grantFailure).isNotNull().isNotInstanceOf(ApiRefusal::class.java)
            assertThat(env.rows(actorA, "IDN-Y")).isEqualTo(before)
            val withdrawFailure = runCatching { env.withdraw(ack) }.exceptionOrNull()
            assertThat(withdrawFailure).isNotNull().isNotInstanceOf(ApiRefusal::class.java)
            assertThat(env.activeAck()?.ref).isEqualTo(ack)
        }

    @Test
    fun `INV-AC-8289 S10 with no acknowledgement active the pair is refused whatever the value`() =
        run { env ->
            env.setAcknowledged("ANY_NUMBER")
            env.granted("IDN-P1", "tenant.admin")
            env.assertRefused(env.refusal { env.grant("IDN-P1", "security.officer") }, ackRequired)
            assertThat(env.holders(Role.SECURITY_OFFICER)).doesNotContain("IDN-P1")
        }

    @Test
    fun `INV-AC-8289 permission matrix is identical after setPolicy of each value`() =
        run { env ->
            val matrix = env.context.getBean(PermissionMatrix::class.java)
            val before = matrix.export()
            env.setAcknowledged("ONE_HOLDER")
            assertThat(matrix.export()).isEqualTo(before)
            env.setAcknowledged("ANY_NUMBER")
            assertThat(matrix.export()).isEqualTo(before)
            assertThat(env.set("ONE_HOLDER_WITHDRAW_BLOCKED").status).isBetween(200, 299)
            assertThat(matrix.export()).isEqualTo(before)
        }

    // ---- serialisation: the row lock on the acknowledgement -------------------------------------------------------

    private fun <T> together(
        count: Int,
        task: (Int) -> T,
    ): List<Result<T>> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(count)
        try {
            val futures =
                (0 until count).map { i ->
                    pool.submit<Result<T>> {
                        start.await()
                        runCatching { task(i) }
                    }
                }
            start.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `INV-AC-8289 SER1 two grants completing different pairs under the default give one 201 and one D1`() =
        repeat(ROUNDS) {
            run { env ->
                env.grant("IDN-P1", "tenant.admin")
                env.grant("IDN-P2", "tenant.admin")
                env.record()
                val people = listOf("IDN-P1", "IDN-P2")
                val results = together(2) { env.grant(people[it], "security.officer").statusCode.value() }
                assertThat(results.mapNotNull { it.getOrNull() }).containsExactly(201)
                val refused = results.mapNotNull { it.exceptionOrNull() }
                assertThat(refused).hasSize(1)
                env.assertRefused(refused.single() as ApiRefusal, d1)
            }
        }

    @Test
    fun `INV-AC-8289 SER2 a grant and a withdrawal started together never give a pair and a withdrawn ack`() =
        repeat(ROUNDS) {
            run { env ->
                env.grant("IDN-P1", "security.officer")
                val ack = env.record()
                val results =
                    together(2) {
                        if (it == 0) {
                            env.grant(actorA, "security.officer").statusCode.value()
                        } else {
                            env.withdraw(ack).statusCode.value()
                        }
                    }
                val grantResult = results[0]
                val withdrawResult = results[1]
                val grantRefusal = grantResult.exceptionOrNull() as? ApiRefusal
                val withdrawRefusal = withdrawResult.exceptionOrNull() as? ApiRefusal
                if (grantResult.isSuccess) {
                    // grant, then the withdrawal is refused with D2
                    assertThat(grantResult.getOrNull()).isEqualTo(201)
                    env.assertRefused(checkNotNull(withdrawRefusal) { "withdrawal not refused: $withdrawResult" }, d2)
                    assertThat(env.activeAck()?.ref).isEqualTo(ack)
                } else {
                    // withdrawal, then the pair is refused
                    assertThat(withdrawResult.getOrNull()).isEqualTo(204)
                    env.assertRefused(checkNotNull(grantRefusal), ackRequired)
                    assertThat(env.holders(Role.SECURITY_OFFICER)).doesNotContain(actorA)
                }
            }
        }

    private companion object {
        const val ROUNDS = 8
    }
}
