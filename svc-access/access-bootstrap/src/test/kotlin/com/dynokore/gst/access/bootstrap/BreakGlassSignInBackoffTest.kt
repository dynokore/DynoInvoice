package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.BreakGlassAlertOutbox
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.json
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.rows
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.withService
import com.dynokore.gst.access.bootstrap.SealFixture.activated
import com.dynokore.gst.access.bootstrap.SealFixture.pair
import com.dynokore.gst.access.bootstrap.SealFixture.signIn
import com.dynokore.gst.access.bootstrap.served.ServedContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration
import java.util.UUID

/**
 * INV-AC-9184 through the real wiring, HTTP and the database (INV-T-13465): the 403 and the 429 with code SRT-012 and
 * `Retry-After`, the counter row, the ACC-002 alerts and the audit entries of `signInBreakGlass` with back-off.
 */
class BreakGlassSignInBackoffTest {
    private val auditType = "com.dynokore.gst.platform.AuditEntryRecorded.v1"
    private val wrong = "wrong-sealing-credential"

    private class Setup(
        val tenant: TenantId,
        val credential: String,
        val bg1: String,
        val bg2: String,
    )

    private fun insertSession(
        tenant: TenantId,
        unlockRef: String,
    ) = BoundaryFixture.asSuperuser { c ->
        c
            .prepareStatement(
                "insert into access.break_glass_session (id, tenant_id, unlock_ref, session_hours, unlocked_at, " +
                    "expires_at, approved_by, status, created_at, created_by, updated_at, updated_by, version) " +
                    "values (?::uuid, ?::uuid, ?, 8, now(), now() + interval '30 days', " +
                    "array['IDN-ADM','IDN-APP'], 'UNLOCKED', now(), 'seed', now(), 'seed', 0)",
            ).use {
                it.setString(1, UUID.randomUUID().toString())
                it.setString(2, tenant.toString())
                it.setString(3, unlockRef)
                it.executeUpdate()
            }
    }

    /** A tenant with its account and the two open unlocks BG-1 and BG-2 (unique references), the clock frozen. */
    private fun ServedContext.setup(): Setup {
        val a = activated()
        pair(a.tenant)
        val suffix = UUID.randomUUID().toString().take(8)
        val bg1 = "BG-1-$suffix"
        val bg2 = "BG-2-$suffix"
        insertSession(a.tenant, bg1)
        insertSession(a.tenant, bg2)
        AcceptFirstAdministratorFixture.run { movableClock() }.freeze()
        return Setup(a.tenant, checkNotNull(a.sealing), bg1, bg2)
    }

    private fun ServedContext.advance(by: Duration) = AcceptFirstAdministratorFixture.run { movableClock() }.advance(by)

    private fun ServedContext.alerts() =
        application
            .getBean(CapturedEvents::class.java)
            .envelopes
            .filter { it.type == BreakGlassAlertOutbox.EVENT_TYPE }
            .map { json(it.dataJson) }

    private fun ServedContext.audits() =
        application
            .getBean(CapturedEvents::class.java)
            .envelopes
            .filter { it.type == auditType && it.dataJson.contains("\"signInBreakGlass\"") }
            .map { it.dataJson }

    private fun retryAfter(response: MockHttpServletResponse): String? = response.getHeader("Retry-After")

    private fun counter(
        tenant: TenantId,
        unlockRef: String,
    ): Int? =
        rows("access.break_glass_signin_counter", tenant)
            .map { json(it) }
            .singleOrNull { it["unlock_ref"].asString() == unlockRef }
            ?.get("failures")
            ?.asInt()

    private fun ServedContext.waitOut(response: MockHttpServletResponse) =
        advance(Duration.ofSeconds(checkNotNull(retryAfter(response)).toLong()))

    /** One wrong credential that waits out a running back-off first; returns the response of the wrong try. */
    private fun ServedContext.failOnce(
        s: Setup,
        unlockRef: String,
    ): MockHttpServletResponse {
        var response = signIn(s.tenant, unlockRef, wrong)
        while (response.status == 429) {
            waitOut(response)
            response = signIn(s.tenant, unlockRef, wrong)
        }
        return response
    }

    /** Failures 5 to 15 on BG-1: one alert at the 5th and one at the 15th, none at 6, 10 or 14. */
    private fun assertAlertsAt5And15(
        served: ServedContext,
        s: Setup,
    ) {
        val alertCounts = mutableMapOf<Int, Int>()
        (5..15).forEach { n ->
            assertThat(served.failOnce(s, s.bg1).status).isEqualTo(403)
            assertThat(counter(s.tenant, s.bg1)).isEqualTo(n)
            alertCounts[n] = served.alerts().size
        }
        assertThat(alertCounts.filterKeys { it in 5..14 }.values.toSet()).containsExactly(1)
        assertThat(alertCounts[15]).isEqualTo(2)
        served.alerts().forEach {
            assertThat(it["accCode"].asString()).isEqualTo("ACC-002")
            assertThat(it["action"].asString()).isEqualTo("SIGN_IN_FAILED")
            assertThat(it["unlockRef"].asString()).isEqualTo(s.bg1)
        }
    }

    @Test
    fun `INV-AC-9184 the back-off sequence answers 403, 429 SRT-012 and 200 with alerts at 5 and 15`() =
        withService { served ->
            val s = served.setup()
            // attempts 1 and 2: 403 with no wait
            repeat(2) {
                val r = served.signIn(s.tenant, s.bg1, wrong)
                assertThat(r.status).describedAs(r.contentAsString).isEqualTo(403)
                assertThat(retryAfter(r)).isNull()
            }
            // attempt 3: 403, then the next sign-in waits 1 s (right or wrong): 429, SRT-012, Retry-After 1
            assertThat(served.signIn(s.tenant, s.bg1, wrong).status).isEqualTo(403)
            listOf(wrong, s.credential).forEach { presented ->
                val waiting = served.signIn(s.tenant, s.bg1, presented)
                assertThat(waiting.status).describedAs(waiting.contentAsString).isEqualTo(429)
                assertThat(json(waiting)["code"].asString()).isEqualTo("SRT-012")
                assertThat(retryAfter(waiting)).isEqualTo("1")
            }
            // a wrong credential for BG-2 while BG-1 waits: 403 and no wait
            val other = served.signIn(s.tenant, s.bg2, wrong)
            assertThat(other.status).isEqualTo(403)
            assertThat(retryAfter(other)).isNull()
            assertThat(counter(s.tenant, s.bg1)).isEqualTo(3)
            assertThat(counter(s.tenant, s.bg2)).isEqualTo(1)
            // half a second later the remaining whole seconds are still 1
            served.advance(Duration.ofMillis(500))
            assertThat(retryAfter(served.signIn(s.tenant, s.bg1, wrong))).isEqualTo("1")
            // 1 s after attempt 3: attempt 4 is counted, the wait is now 2 s
            served.advance(Duration.ofMillis(500))
            assertThat(served.signIn(s.tenant, s.bg1, wrong).status).isEqualTo(403)
            assertThat(retryAfter(served.signIn(s.tenant, s.bg1, wrong))).isEqualTo("2")
            assertThat(served.alerts()).isEmpty()
            assertAlertsAt5And15(served, s)
            // the right credential after the wait: 200, the counter back to 0
            val waiting = served.signIn(s.tenant, s.bg1, s.credential)
            assertThat(waiting.status).isEqualTo(429)
            served.waitOut(waiting)
            val ok = served.signIn(s.tenant, s.bg1, s.credential)
            assertThat(ok.status).describedAs(ok.contentAsString).isEqualTo(200)
            assertThat(counter(s.tenant, s.bg1)).isEqualTo(0)
            // BG-2 kept its own counter
            assertThat(counter(s.tenant, s.bg2)).isEqualTo(1)
        }

    @Test
    fun `INV-AC-9184 a right credential before the 3rd failure resets and no wait follows`() =
        withService { served ->
            val s = served.setup()
            repeat(2) { assertThat(served.signIn(s.tenant, s.bg1, wrong).status).isEqualTo(403) }
            assertThat(served.signIn(s.tenant, s.bg1, s.credential).status).isEqualTo(200)
            assertThat(counter(s.tenant, s.bg1)).isEqualTo(0)
            repeat(2) {
                val r = served.signIn(s.tenant, s.bg1, wrong)
                assertThat(r.status).isEqualTo(403)
                assertThat(retryAfter(r)).isNull()
            }
        }

    @Test
    fun `INV-AC-9184 another tenant's wrong credential for the same unlockRef is 404 and no row`() =
        withService { served ->
            val s = served.setup()
            val b = served.activated()
            served.pair(b.tenant)
            repeat(4) {
                val r = served.signIn(b.tenant, s.bg1, wrong)
                assertThat(r.status).describedAs(r.contentAsString).isEqualTo(404)
            }
            assertThat(rows("access.break_glass_signin_counter", b.tenant)).isEmpty()
            assertThat(rows("access.break_glass_signin_counter", s.tenant)).isEmpty()
        }

    @Test
    fun `INV-AC-9184 after 100 wrong credentials the right one answers 200, alerts and audit hold the count`() =
        withService { served ->
            val s = served.setup()
            (1..100).forEach { n ->
                assertThat(served.failOnce(s, s.bg1).status).isEqualTo(403)
                val alerts = served.alerts()
                val expected = if (n < 5) 0 else (n - 5) / 10 + 1
                assertThat(alerts).describedAs("alerts after failure $n").hasSize(expected)
            }
            assertThat(counter(s.tenant, s.bg1)).isEqualTo(100)
            val waiting = served.signIn(s.tenant, s.bg1, s.credential)
            assertThat(waiting.status).isEqualTo(429)
            assertThat(retryAfter(waiting)).isEqualTo("60")
            served.advance(Duration.ofSeconds(60))
            val ok = served.signIn(s.tenant, s.bg1, s.credential)
            assertThat(ok.status).describedAs(ok.contentAsString).isEqualTo(200)
            assertThat(counter(s.tenant, s.bg1)).isEqualTo(0)
            // the audit entries: one per alert count, the count as detail, the bound user as actor
            val audits = served.audits()
            assertThat(audits).hasSize(10)
            val nodes = audits.map { json(it) }
            assertThat(nodes.map { it.toString() }).allSatisfy {
                assertThat(it).doesNotContain(wrong).doesNotContain(s.credential)
            }
            assertThat(audits.joinToString()).contains("failures=5", "failures=15", "failures=25", "failures=95")
            nodes.forEach { node ->
                val actor = node.findValue("actor")?.asString()
                assertThat(actor).isEqualTo(SealFixture.ADMIN)
                assertThat(actor).isNotEqualTo("break-glass:${s.bg1}").isNotEqualTo("tenant.admin").isNotEmpty()
            }
        }
}
