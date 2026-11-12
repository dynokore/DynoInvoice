package com.dynokore.gst.notification.domain.alerting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class BacklogEvaluationTest {
    private fun m(
        depth: Long = 0,
        age: Long? = 0,
        rate: Double = 0.0,
        unack: Long = 0,
    ) = BacklogMeasurement("E1", "2026-Q3", Instant.parse("2026-11-12T00:00:00Z"), depth, age, rate, unack)

    private val all = BacklogThresholds(10, 100, 0.5, 5)

    private fun backlog(
        m: BacklogMeasurement,
        t: BacklogThresholds? = all,
    ) = BacklogEvaluation.decide(m, AlertClass.BACKLOG, t)

    @Test
    fun `INV-AC-20815 queue depth strictly above its maximum breaches and equal does not`() {
        backlog(m(depth = 11)).breached shouldBe listOf(BacklogValue.QUEUE_DEPTH)
        backlog(m(depth = 10)).kind shouldBe BacklogDecisionKind.WITHIN_THRESHOLD
        backlog(m(depth = 10)).breached shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20815 oldest unsent age strictly above breaches, equal does not, absent never`() {
        backlog(m(age = 101)).breached shouldBe listOf(BacklogValue.OLDEST_UNSENT_AGE)
        backlog(m(age = 100)).kind shouldBe BacklogDecisionKind.WITHIN_THRESHOLD
        backlog(m(age = null)).kind shouldBe BacklogDecisionKind.WITHIN_THRESHOLD
        backlog(m(age = null)).notConfigured shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20815 failure rate strictly above breaches and equal does not`() {
        backlog(m(rate = 0.51)).breached shouldBe listOf(BacklogValue.FAILURE_RATE)
        backlog(m(rate = 0.5)).kind shouldBe BacklogDecisionKind.WITHIN_THRESHOLD
    }

    @Test
    fun `INV-AC-20815 unacknowledged count is compared only for MISSING_ACKNOWLEDGEMENT`() {
        val high = m(depth = 99, age = 999, rate = 1.0, unack = 6)
        val d = BacklogEvaluation.decide(high, AlertClass.MISSING_ACKNOWLEDGEMENT, all)
        d.kind shouldBe BacklogDecisionKind.BREACHED
        d.breached shouldBe listOf(BacklogValue.UNACKNOWLEDGED_COUNT)
        BacklogEvaluation.decide(m(unack = 5), AlertClass.MISSING_ACKNOWLEDGEMENT, all).kind shouldBe
            BacklogDecisionKind.WITHIN_THRESHOLD
        backlog(m(unack = 99)).kind shouldBe BacklogDecisionKind.WITHIN_THRESHOLD
    }

    @Test
    fun `INV-AC-20815 three values breached together give one BREACHED decision listing all`() {
        val d = backlog(m(depth = 11, age = 101, rate = 0.9))
        d.kind shouldBe BacklogDecisionKind.BREACHED
        d.breached shouldBe listOf(BacklogValue.QUEUE_DEPTH, BacklogValue.OLDEST_UNSENT_AGE, BacklogValue.FAILURE_RATE)
    }

    @Test
    fun `INV-AC-20815 an absent threshold never breaches and is listed as not configured`() {
        val d = backlog(m(depth = 1000, age = 1000, rate = 1.0), BacklogThresholds(maxOldestUnsentAgeSeconds = 2000))
        d.kind shouldBe BacklogDecisionKind.WITHIN_THRESHOLD
        d.notConfigured shouldBe listOf(BacklogValue.QUEUE_DEPTH, BacklogValue.FAILURE_RATE)
        val partly = backlog(m(depth = 11), BacklogThresholds(maxQueueDepth = 10))
        partly.kind shouldBe BacklogDecisionKind.BREACHED
        partly.notConfigured shouldBe listOf(BacklogValue.OLDEST_UNSENT_AGE, BacklogValue.FAILURE_RATE)
    }

    @Test
    fun `INV-AC-20815 no thresholds or all absent gives NOT_CONFIGURED for each class`() {
        for (t in listOf(null, BacklogThresholds())) {
            val b = backlog(m(depth = 1000), t)
            b.kind shouldBe BacklogDecisionKind.NOT_CONFIGURED
            b.breached shouldBe emptyList()
            b.notConfigured shouldBe
                listOf(BacklogValue.QUEUE_DEPTH, BacklogValue.OLDEST_UNSENT_AGE, BacklogValue.FAILURE_RATE)
            val u = BacklogEvaluation.decide(m(unack = 1000), AlertClass.MISSING_ACKNOWLEDGEMENT, t)
            u.kind shouldBe BacklogDecisionKind.NOT_CONFIGURED
            u.notConfigured shouldBe listOf(BacklogValue.UNACKNOWLEDGED_COUNT)
        }
    }

    @Test
    fun `INV-AC-20815 the trigger constant equals BACKLOG_THRESHOLD exactly`() {
        BACKLOG_ALERT_TRIGGER shouldBe "BACKLOG_THRESHOLD"
        (BACKLOG_ALERT_TRIGGER == "BACKLOG_THRESHOLDS") shouldBe false
        (BACKLOG_ALERT_TRIGGER == "backlog_threshold") shouldBe false
    }
}
