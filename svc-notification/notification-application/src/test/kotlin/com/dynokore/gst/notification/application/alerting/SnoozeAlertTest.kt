package com.dynokore.gst.notification.application.alerting

import com.dynokore.gst.notification.domain.alerting.AlertClass
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.Principal
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class SnoozeAlertTest {
    private val fixture = ReplicaFixture(AlertActions.SNOOZE_ALERT)
    private val now = fixture.clock.instant()
    private val hardStop = Instant.parse("2026-04-27T00:00:00Z")
    private var hardStopCalls = 0
    private var knownHardStop: Instant? = hardStop
    private val sources =
        HardStopSource { _, _, _ ->
            hardStopCalls++
            knownHardStop
        }

    private var clockCalls = 0
    private val countingClock =
        object : java.time.Clock() {
            override fun getZone() = fixture.clock.zone

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant(): Instant {
                clockCalls++
                return fixture.clock.instant()
            }
        }

    private fun handle(
        until: Instant,
        alertClass: AlertClass = AlertClass.DEADLINE,
        principal: Principal = PERMITTED,
        service: SnoozeAlert = SnoozeAlert(fixture.replica, countingClock, sources),
    ) = service.handle(
        SnoozeAlertCommand(TENANT, principal, ENTITY, "alert-1", alertClass, until),
        fixture.deadline(),
    )

    @Test
    fun `INV-AC-10573 a snooze of 7 days is Accepted`() {
        handle(now.plus(Duration.ofDays(7))) shouldBe SnoozeAlertResult.Accepted(now.plus(Duration.ofDays(7)))
    }

    @Test
    fun `INV-AC-10573 a snooze of 9 days is refused POL-013 BEYOND_MAX_SNOOZE`() {
        handle(now.plus(Duration.ofDays(9))) shouldBe SnoozeAlertResult.Refused("POL-013", "BEYOND_MAX_SNOOZE")
    }

    @Test
    fun `INV-AC-10573 the hard stop itself is Accepted`() {
        handle(hardStop) shouldBe SnoozeAlertResult.Accepted(hardStop)
    }

    @Test
    fun `INV-AC-10573 data set 1 one second after the hard stop is BEYOND_MAX_SNOOZE not BEYOND_HARD_STOP`() {
        // the 7-day bound is checked before the hard stop (ruling on INV-Q-12403)
        val result = handle(hardStop.plusSeconds(1))
        result shouldBe SnoozeAlertResult.Refused("POL-013", "BEYOND_MAX_SNOOZE")
        (result as SnoozeAlertResult.Refused).reason shouldNotBe "BEYOND_HARD_STOP"
    }

    @Test
    fun `INV-AC-10573 data set 2 hard stop at now plus 3 days is Accepted and one second after it BEYOND_HARD_STOP`() {
        val stop = now.plus(Duration.ofDays(3))
        knownHardStop = stop
        for (alertClass in listOf(AlertClass.DEADLINE, AlertClass.COVERAGE)) {
            handle(stop, alertClass) shouldBe SnoozeAlertResult.Accepted(stop)
            handle(stop.plusSeconds(1), alertClass) shouldBe SnoozeAlertResult.Refused("POL-013", "BEYOND_HARD_STOP")
        }
    }

    @Test
    fun `INV-AC-10573 a snooze equal to now is refused POL-013 NOT_IN_FUTURE`() {
        handle(now) shouldBe SnoozeAlertResult.Refused("POL-013", "NOT_IN_FUTURE")
    }

    @Test
    fun `INV-AC-10573 DEADLINE with no hard stop is refused POL-014 HARD_STOP_UNKNOWN`() {
        knownHardStop = null
        handle(now.plus(Duration.ofDays(1))) shouldBe SnoozeAlertResult.Refused("POL-014", "HARD_STOP_UNKNOWN")
    }

    @Test
    fun `INV-AC-10573 the other classes never ask for a hard stop`() {
        for (alertClass in AlertClass.entries.filter { it != AlertClass.DEADLINE && it != AlertClass.COVERAGE }) {
            handle(now.plus(Duration.ofDays(7)), alertClass) shouldBe
                SnoozeAlertResult.Accepted(now.plus(Duration.ofDays(7)))
        }
        hardStopCalls shouldBe 0
        handle(now.plus(Duration.ofDays(1)), AlertClass.COVERAGE)
        hardStopCalls shouldBe 1
    }

    @Test
    fun `INV-AC-10573 a refused principal is Forbidden and neither clock nor hard stop is called`() {
        handle(now.plus(Duration.ofDays(1)), principal = NO_GRANT) shouldBe
            SnoozeAlertResult.Forbidden(DecisionReason.NO_GRANT)
        handle(now.plus(Duration.ofDays(1)), principal = REVOKED) shouldBe
            SnoozeAlertResult.Forbidden(DecisionReason.REVOKED)
        val lagging = SnoozeAlert(fixture.lagging(), countingClock, sources)
        handle(now.plus(Duration.ofDays(1)), service = lagging) shouldBe
            SnoozeAlertResult.Forbidden(DecisionReason.AUTHORITY_UNAVAILABLE)
        hardStopCalls shouldBe 0
        clockCalls shouldBe 0
    }
}
