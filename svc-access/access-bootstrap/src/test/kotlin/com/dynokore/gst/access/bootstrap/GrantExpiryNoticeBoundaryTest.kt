package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.AccessAlertOutbox
import com.dynokore.gst.access.adapters.events.OutboxLinkMessages
import com.dynokore.gst.access.application.grant.ExpiryAlertLog
import com.dynokore.gst.access.application.grant.ExpiryNoticeLog
import com.dynokore.gst.access.application.grant.GrantContacts
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.grant.ScanGrantExpiryNotices
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantHeader
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID

/**
 * The grant expiry-notice scan at the service boundary (INV-T-15799; INV-REQ-893, INV-REQ-1267; owner answer
 * INV-Q-13245
 * A): real database, real outbox mapper, the real scan, the clock moved from day to day; only the directory is a stub.
 * VP-06 is `planned` in the pinned catalogue, so the default 14 and 3 days applies.
 */
class GrantExpiryNoticeBoundaryTest {
    private val zone = ServiceDefaults.BUSINESS_ZONE
    private val mapper = JsonMapper.builder().build()
    private val addresses =
        mutableMapOf(
            "ext-holder" to "holder@example.sg",
            "seed" to "admin@example.sg",
        )
    private val contacts = GrantContacts { addresses[it.value] }

    private class Env(
        val context: ApplicationContext,
        val tenant: TenantId,
    ) {
        val tenants = context.getBean(TenantContext::class.java)
        val tx = context.getBean(TransactionRunner::class.java)
        val clock = context.getBean(java.time.Clock::class.java) as MovableClock
        val captured = context.getBean(CapturedEvents::class.java)

        fun <T> bound(block: () -> T): T = tenants.bind(tenant, "seed") { tx.inTransaction(block) }

        /** An ACTIVE grant of [identity] of [kind] that ends at [until]; created by the actor `seed`. */
        fun seed(
            kind: GranteeKind,
            until: Instant,
            identity: String = "ext-holder",
        ): String {
            val ref = "GRT-" + UUID.randomUUID().toString().take(8)
            val grants = context.getBean(GrantRepository::class.java)
            val issued =
                Grant.issue(
                    GrantHeader(GrantRef(ref), TenantRef(tenant.toString()), IdentityRef(identity)),
                    Role.AUDITOR,
                    Scope.Entity(EntityRef("ENT-1")),
                    ValidityWindow(clock.instant().minus(java.time.Duration.ofDays(1)), until),
                )
            bound {
                grants.lockIdentity(IdentityRef(identity), kind)
                grants.insert(issued)
            }
            return ref
        }

        /** Moves the clock to [at], which is later than the clock reads now. */
        fun moveTo(at: Instant) {
            val now = clock.freeze()
            clock.advance(java.time.Duration.between(now, at))
        }

        fun scan() =
            tenants.bind(tenant, "svc:test") {
                tx.inTransaction { context.getBean(ScanGrantExpiryNotices::class.java).scan(clock.instant()) }
            }

        fun messages() = captured.envelopes.filter { it.type == OutboxLinkMessages.EVENT_TYPE && it.tenant == tenant }

        fun alerts() = captured.envelopes.filter { it.type == AccessAlertOutbox.EVENT_TYPE && it.tenant == tenant }

        fun noticed(ref: String) = bound { context.getBean(ExpiryNoticeLog::class.java).noticedDays(GrantRef(ref)) }

        fun superseded(ref: String) =
            bound { context.getBean(ExpiryNoticeLog::class.java).supersededDays(GrantRef(ref)) }
    }

    private fun withEnv(block: (Env) -> Unit) {
        BoundaryFixture
            .runner()
            .withBean(GrantContacts::class.java, { contacts })
            .run { context ->
                context.startupFailure?.let { throw it }
                block(Env(context, TenantId(UUID.randomUUID())))
            }
    }

    private fun recipientsOf(payload: tools.jackson.databind.JsonNode): List<String> =
        payload.path("recipients").let { n -> (0 until n.size()).map { n[it].asString() } }

    private fun at(
        date: LocalDate,
        hour: Int = 9,
    ): Instant = ZonedDateTime.of(date, LocalTime.of(hour, 0), zone).toInstant()

    private fun inFutureYear(
        month: Int,
        day: Int,
    ): LocalDate = LocalDate.now(zone).let { LocalDate.of(it.year + 1, month, day) }

    @Test
    fun `INV-AC-2250 a grant ending on the 20th sends notices on the 6th and the 17th`() {
        withEnv { env ->
            val end = inFutureYear(8, 20)
            env.seed(GranteeKind.STAFF, at(end, 17))
            env.moveTo(at(end.withDayOfMonth(5)))
            env.scan()
            assertThat(env.messages()).isEmpty()
            env.moveTo(at(end.withDayOfMonth(6)))
            env.scan()
            env.scan()
            assertThat(env.messages()).hasSize(1)
            env.moveTo(at(end.withDayOfMonth(16)))
            env.scan()
            assertThat(env.messages()).hasSize(1)
            env.moveTo(at(end.withDayOfMonth(17)))
            env.scan()
            val messages = env.messages()
            assertThat(messages).hasSize(2)
            messages.forEach {
                val payload = mapper.readTree(it.dataJson)
                assertThat(payload.path("template").asString()).isEqualTo("grant-expiry-notice")
                assertThat(recipientsOf(payload))
                    .containsExactly("holder@example.sg", "admin@example.sg")
                assertThat(it.entity.value).isEqualTo("ENT-1")
            }
        }
    }

    @Test
    fun `INV-AC-1933 an external grant expiring on 30 June notices on 16 and 27 June and ACC-003 on expiry`() {
        withEnv { env ->
            val end = inFutureYear(6, 30)
            val ref = env.seed(GranteeKind.EXTERNAL, at(end, 12))
            env.moveTo(at(LocalDate.of(end.year, 6, 16)))
            env.scan()
            assertThat(env.noticed(ref)).containsExactly(14)
            env.moveTo(at(LocalDate.of(end.year, 6, 27)))
            env.scan()
            assertThat(env.noticed(ref)).containsExactlyInAnyOrder(14, 3)
            assertThat(env.messages()).hasSize(2)
            assertThat(env.alerts()).isEmpty()
            env.moveTo(at(LocalDate.of(end.year, 7, 1)))
            env.scan()
            env.scan()
            val alert = env.alerts().single()
            assertThat(alert.dataJson).contains("ACC-003")
            assertThat(alert.entity.value).isEqualTo("ENT-1")
            assertThat(env.bound { env.context.getBean(ExpiryAlertLog::class.java).wasRaised(GrantRef(ref)) }).isTrue()
            assertThat(env.messages()).hasSize(2)
        }
    }

    @Test
    fun `an expired staff grant raises no ACC-003`() {
        withEnv { env ->
            val end = inFutureYear(6, 30)
            env.seed(GranteeKind.STAFF, at(end, 12))
            env.moveTo(at(end.plusDays(1)))
            env.scan()
            assertThat(env.alerts()).isEmpty()
        }
    }

    @Test
    fun `a grant seen only 2 days before expiry sends the 3 day notice and supersedes the 14 day one`() {
        withEnv { env ->
            val end = inFutureYear(8, 20)
            val ref = env.seed(GranteeKind.STAFF, at(end, 17))
            env.moveTo(at(end.minusDays(2)))
            env.scan()
            env.scan()
            assertThat(env.messages()).hasSize(1)
            assertThat(env.noticed(ref)).containsExactly(3)
            assertThat(env.superseded(ref)).containsExactly(14)
        }
    }

    @Test
    fun `an administrator with no address leaves the holder as the only recipient and counts one`() {
        withEnv { env ->
            addresses.remove("seed")
            val end = inFutureYear(8, 20)
            env.seed(GranteeKind.STAFF, at(end, 17))
            env.moveTo(at(end.minusDays(14)))
            env.scan()
            val payload = mapper.readTree(env.messages().single().dataJson)
            assertThat(recipientsOf(payload)).containsExactly("holder@example.sg")
            val registry = env.context.getBean(MeterRegistry::class.java)
            assertThat(registry.counter(GrantExpiryNoticeTelemetry.METRIC).count()).isEqualTo(1.0)
        }
    }

    @Test
    fun `no resolved address requests nothing, records nothing and retries at the next scan`() {
        withEnv { env ->
            addresses.clear()
            val end = inFutureYear(8, 20)
            val ref = env.seed(GranteeKind.STAFF, at(end, 17))
            env.moveTo(at(end.minusDays(14)))
            env.scan()
            assertThat(env.messages()).isEmpty()
            assertThat(env.noticed(ref)).isEmpty()
            addresses["ext-holder"] = "holder@example.sg"
            env.scan()
            assertThat(env.messages()).hasSize(1)
        }
    }

    @Test
    fun `the scheduler claims the tenant with a time-limited grant and scans it bound`() {
        withEnv { env ->
            val end = inFutureYear(8, 20)
            env.seed(GranteeKind.STAFF, at(end, 17))
            env.moveTo(at(end.minusDays(14)))
            env.context.getBean(GrantExpiryNoticeScheduler::class.java).runOnce()
            assertThat(env.messages()).hasSize(1)
        }
    }
}
