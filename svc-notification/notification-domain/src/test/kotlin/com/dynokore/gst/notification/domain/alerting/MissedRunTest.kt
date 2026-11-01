package com.dynokore.gst.notification.domain.alerting

import com.dynokore.gst.notification.domain.alerting.MissedRunDecision.Raise
import com.dynokore.gst.notification.domain.alerting.MissedRunDecision.Withhold
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class MissedRunTest {
    private val key = JobKey("svc-a", "job-1")
    private val at = Instant.parse("2026-11-02T02:00:00Z")
    private val next = Instant.parse("2026-11-03T02:00:00Z")
    private val tol = Duration.parse("PT2H")
    private val exp = JobExpectation(key, at, tol)
    private val ops = listOf("deployment.operator:jane.tan")

    @Test
    fun `INV-AC-15938 nothing is missed at the deadline and the job is missed one nanosecond later`() {
        MissedRunRules.missed(listOf(exp), Instant.parse("2026-11-02T04:00:00Z")) shouldBe emptyList()
        MissedRunRules.missed(listOf(exp), Instant.parse("2026-11-02T04:00:00.000000001Z")) shouldBe listOf(exp)
    }

    @Test
    fun `INV-AC-15938 a registration after a missed tick keeps expectedAt`() {
        val r = MissedRunRules.register(exp, key, next, Duration.ofHours(1))
        r shouldBe exp.copy(tolerance = Duration.ofHours(1))
        MissedRunRules.register(null, key, next, tol) shouldBe JobExpectation(key, next, tol)
    }

    @Test
    fun `INV-AC-15938 a run for the expected tick moves the expectation to its next run time`() {
        MissedRunRules.recordRun(exp, RecordedRun(key, at, next), null) shouldBe exp.copy(expectedAt = next)
        MissedRunRules.recordRun(null, RecordedRun(key, at, next), null) shouldBe null
        MissedRunRules.recordRun(null, RecordedRun(key, at, next), tol) shouldBe JobExpectation(key, next, tol)
    }

    @Test
    fun `INV-AC-15938 a run one tick earlier leaves the expectation unchanged`() {
        val earlier = at.minus(Duration.ofDays(1))
        MissedRunRules.recordRun(exp, RecordedRun(key, earlier, at), null) shouldBe exp
    }

    @Test
    fun `INV-AC-15938 a FAILED-outcome run moves the expectation the same way`() {
        // The outcome is not an input of recordRun: a failed run is a run.
        MissedRunRules.recordRun(exp, RecordedRun(key, at, next), tol) shouldBe exp.copy(expectedAt = next)
    }

    @Test
    fun `INV-AC-15938 keys differing only in job are two expectations with their own alert reference`() {
        val other = exp.copy(key = JobKey("svc-a", "job-0"))
        val now = Instant.parse("2026-11-02T05:00:00Z")
        MissedRunRules.missed(listOf(exp, other), now) shouldBe listOf(other, exp)
        MissedRunAlert.alertRef(exp) shouldBe "missed-run:svc-a:job-1:" + PLATFORM_ENTITY_REF + ":2026-11-02T02:00:00Z"
        MissedRunAlert.alertRef(other) shouldBe
            "missed-run:svc-a:job-0:" + PLATFORM_ENTITY_REF + ":2026-11-02T02:00:00Z"
    }

    @Test
    fun `INV-AC-15938 a repeated key in the missed input is refused`() {
        shouldThrow<IllegalArgumentException> {
            MissedRunRules.missed(
                listOf(exp, exp.copy(tolerance = Duration.ZERO)),
                next,
            )
        }
    }

    @Test
    fun `INV-AC-15938 invalid values are refused`() {
        shouldThrow<IllegalArgumentException> { JobKey(" ", "j") }
        shouldThrow<IllegalArgumentException> { JobKey("s", "") }
        shouldThrow<IllegalArgumentException> { JobExpectation(key, at, Duration.ofSeconds(-1)) }
        shouldThrow<IllegalArgumentException> { RecordedRun(key, at, at) }
    }

    @Test
    fun `INV-AC-15938 the alert is one platform MISSED_RUN alert and a recorded reference withholds it`() {
        val d = MissedRunAlert.decide(exp, ops + listOf(" ", ops[0]), emptySet())
        d shouldBe
            Raise(
                MissedRunAlertDraft(
                    alertClass = "MISSED_RUN",
                    trigger = "JOB_NOT_RUN",
                    entityRef = PLATFORM_ENTITY_REF,
                    alertRef = MissedRunAlert.alertRef(exp),
                    recipients = ops,
                    tiers = emptyList(),
                    channel = AccreditationChannel.EMAIL,
                ),
            )
        MissedRunAlert.decide(exp, ops, setOf(MissedRunAlert.alertRef(exp))) shouldBe
            Withhold(MissedRunWithheld.ALREADY_ALERTED)
        ROLE_DEPLOYMENT_OPERATOR shouldBe "deployment.operator"
    }

    @Test
    fun `INV-AC-15938 an empty or blank-only recipient list is withheld as NO_RECIPIENT`() {
        MissedRunAlert.decide(exp, emptyList(), emptySet()) shouldBe Withhold(MissedRunWithheld.NO_RECIPIENT)
        MissedRunAlert.decide(exp, listOf("", "  "), emptySet()) shouldBe Withhold(MissedRunWithheld.NO_RECIPIENT)
    }
}
