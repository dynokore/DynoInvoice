package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.BreakGlassAlertOutbox
import com.dynokore.gst.access.adapters.http.ApiConflict
import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.ApiRefusalAdvice
import com.dynokore.gst.access.adapters.http.BreakGlassController
import com.dynokore.gst.access.application.idp.DirectoryGroupMembers
import com.dynokore.gst.access.application.idp.IdentityProviderAvailability
import com.dynokore.gst.access.application.idp.IdentityProviderProbe
import com.dynokore.gst.access.contract.model.BreakGlassResponse
import com.dynokore.gst.access.contract.model.UnlockBreakGlassRequest
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A stub identity provider whose discovery document and key set answer or fail on command, read through the
 * availability component the way the schedule reads them (INV-AC-9185). [tick] is one scheduled probe, 15 s on the
 * stub clock after the one before.
 */
class StubIdentityProvider(
    private val clock: MovableClock,
) {
    val discoveryAnswers = AtomicBoolean(true)
    val keySetAnswers = AtomicBoolean(true)
    val availability =
        IdentityProviderAvailability(
            "http://127.0.0.1:1/realms/boundary-test",
            IdentityProviderProbe { discoveryAnswers.get() && keySetAnswers.get() },
            3,
        )

    fun tick() {
        clock.advance(Duration.ofSeconds(15))
        availability.probeOnce()
    }

    fun failBoth() {
        discoveryAnswers.set(false)
        keySetAnswers.set(false)
    }
}

/** The override of `unlockBreakGlass` for an identity provider that counts as available or unavailable (INV-AC-9185). */
class IdpOverrideBreakGlassTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val mapper = JsonMapper.builder().build()

    private inner class Env(
        val context: AssertableWebApplicationContext,
        val idp: StubIdentityProvider,
    ) {
        val tenants = context.getBean(TenantContext::class.java)
        val api = context.getBean(BreakGlassController::class.java)
        val captured = context.getBean(CapturedEvents::class.java)

        init {
            Seeds.grant(context, tenant, SeedSpec("IDN-ADM", Role.TENANT_ADMIN))
            Seeds.grant(context, tenant, SeedSpec("IDN-APP", Role.TAX_APPROVER))
            captured.envelopes.clear()
        }

        fun unlock(
            reason: UnlockBreakGlassRequest.OverrideReason? = null,
            vararg approvers: String = arrayOf("IDN-ADM", "IDN-APP"),
        ) = tenants.bind(tenant, "IDN-ADM") {
            val request = UnlockBreakGlassRequest(tenant.toString(), approvers.toList(), reason)
            api.unlockBreakGlass(UUID.randomUUID(), request)
        }

        fun sessions(): Int =
            BoundaryFixture.asSuperuser { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery("SELECT count(*) FROM access.break_glass_session WHERE tenant_id = '$tenant'")
                        .use { rows -> rows.next().let { rows.getInt(1) } }
                }
            }

        fun alerts() = captured.envelopes.filter { it.type == BreakGlassAlertOutbox.EVENT_TYPE }

        fun audits() =
            captured.envelopes.filter {
                it.type == "com.dynokore.gst.platform.AuditEntryRecorded.v1" &&
                    it.dataJson.contains("\"unlockBreakGlass\"")
            }

        /** The refusal 409: a problem body with a detail text and no catalogue code, and no session opened. */
        fun assertConflict(call: () -> Any?) {
            val before = sessions()
            val refused = catchThrowable { call() }
            assertThat(refused).isInstanceOf(ApiConflict::class.java)
            val conflict = ApiRefusalAdvice().conflict(refused as ApiConflict)
            assertThat(conflict.statusCode.value()).isEqualTo(409)
            val problem = mapper.readTree(conflict.body)
            assertThat(problem.has("code")).isFalse()
            assertThat(problem.path("detail").asString()).isNotBlank()
            assertThat(sessions()).isEqualTo(before)
            assertThat(alerts()).isEmpty()
            assertThat(audits()).isEmpty()
        }

        /** In every case a pair of one person is refused as before, whatever the reason and the availability. */
        fun assertPairOfOneRefused() {
            val before = sessions()
            UnlockBreakGlassRequest.OverrideReason.entries.map { it }.plus(null).forEach { reason ->
                assertThatThrownBy { unlock(reason, "IDN-ADM", "IDN-ADM") }
                    .isInstanceOf(ApiRefusal::class.java)
                    .hasMessageContaining("SRT-001")
            }
            assertThat(sessions()).isEqualTo(before)
        }

        fun detail(): String? {
            val node = mapper.readTree(audits().single().dataJson).path("reason")
            return if (node.isNull || node.isMissingNode) null else node.asString()
        }

        /** The raw request through the servlet layer, so the value is read as text (400 comes from the value). */
        fun post(overrideReason: String): Int {
            val body =
                mapper.writeValueAsString(
                    mapOf(
                        "tenantRef" to tenant.toString(),
                        "approvedBy" to listOf("IDN-ADM", "IDN-APP"),
                        "overrideReason" to overrideReason,
                    ),
                )
            return MockMvcBuilders
                .webAppContextSetup(context.sourceApplicationContext as WebApplicationContext)
                .build()
                .perform(
                    MockMvcRequestBuilders
                        .post("/internal/v1/access/break-glass")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andReturn()
                .response.status
        }
    }

    private fun run(block: (Env) -> Unit) {
        val clock = MovableClock()
        val idp = StubIdentityProvider(clock)
        BoundaryFixture
            .runner()
            .withBean(DirectoryGroupMembers::class.java, { DirectoryGroupMembers { emptySet() } })
            .withAllowBeanDefinitionOverriding(true)
            .withBean("identityProviderAvailability", IdentityProviderAvailability::class.java, { idp.availability })
            .withBean("clock", Clock::class.java, { clock })
            .run { context ->
                context.startupFailure?.let { throw it }
                assertThat(context.getBean(IdentityProviderAvailability::class.java)).isSameAs(idp.availability)
                block(Env(context, idp))
            }
    }

    private val outage = UnlockBreakGlassRequest.OverrideReason.IDP_PARTIAL_OUTAGE

    @Test
    fun `INV-AC-9185 a with the probe never run the unlock without a reason is 409 and opens nothing`() =
        run { env ->
            env.assertConflict { env.unlock() }
            env.assertPairOfOneRefused()
        }

    @Test
    fun `INV-AC-9185 b after one run with discovery and keys answering the unlock without a reason is 409`() =
        run { env ->
            env.idp.tick()
            assertThat(env.idp.availability.isAvailable()).isTrue()
            env.assertConflict { env.unlock() }
            env.assertPairOfOneRefused()
        }

    @Test
    fun `INV-AC-9185 c with the provider available the reason IDP_PARTIAL_OUTAGE opens the session and is recorded`() =
        run { env ->
            env.idp.tick()
            val response = env.unlock(outage)
            assertThat(response.statusCode.value()).isEqualTo(201)
            assertThat(response.body!!.status).isEqualTo(BreakGlassResponse.Status.UNLOCKED)
            assertThat(env.sessions()).isEqualTo(1)
            assertThat(env.detail()).isEqualTo("IDP_PARTIAL_OUTAGE")
            val alert = env.alerts().single()
            assertThat(alert.dataJson).contains("\"accCode\":\"ACC-002\"", "\"action\":\"UNLOCK\"")
            assertThat(
                mapper.readTree(alert.dataJson).path("overrideReason").asString(),
            ).isEqualTo("IDP_PARTIAL_OUTAGE")
            env.assertPairOfOneRefused()
        }

    @Test
    fun `INV-AC-9185 d after 3 failed probes the unlock without a reason is 201 and records no reason`() =
        run { env ->
            env.idp.failBoth()
            repeat(3) { env.idp.tick() }
            assertThat(env.idp.availability.isAvailable()).isFalse()
            val response = env.unlock()
            assertThat(response.statusCode.value()).isEqualTo(201)
            assertThat(env.sessions()).isEqualTo(1)
            assertThat(env.detail()).isNull()
            val alert = env.alerts().single()
            assertThat(alert.dataJson).contains("\"accCode\":\"ACC-002\"").doesNotContain("overrideReason")
            env.assertPairOfOneRefused()
        }

    @Test
    fun `INV-AC-9185 e after only 2 failed probes the unlock without a reason is 409`() =
        run { env ->
            env.idp.failBoth()
            repeat(2) { env.idp.tick() }
            assertThat(env.idp.availability.isAvailable()).isTrue()
            env.assertConflict { env.unlock() }
            env.assertPairOfOneRefused()
        }

    @Test
    fun `INV-AC-9185 f a reason of idp_partial_outage in lower case is 400 at the time of case d and opens nothing`() =
        run { env ->
            env.idp.failBoth()
            repeat(3) { env.idp.tick() }
            assertThat(env.idp.availability.isAvailable()).isFalse()
            assertThat(env.post("idp_partial_outage")).isEqualTo(400)
            assertThat(env.sessions()).isZero()
            assertThat(env.alerts()).isEmpty()
            assertThat(env.audits()).isEmpty()
            env.assertPairOfOneRefused()
        }

    @Test
    fun `INV-AC-9185 f a reason IDP_PARTIAL_OUTAGE with a trailing space is 400 with the provider available`() =
        run { env ->
            env.idp.tick()
            assertThat(env.post("IDP_PARTIAL_OUTAGE ")).isEqualTo(400)
            assertThat(env.sessions()).isZero()
            assertThat(env.alerts()).isEmpty()
            assertThat(env.audits()).isEmpty()
        }
}
