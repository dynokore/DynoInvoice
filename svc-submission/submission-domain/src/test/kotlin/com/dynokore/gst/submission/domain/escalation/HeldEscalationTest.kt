package com.dynokore.gst.submission.domain.escalation

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.catalogue.Severity
import com.dynokore.gst.submission.domain.escalation.EscalationDecision.Escalate
import com.dynokore.gst.submission.domain.escalation.EscalationDecision.Wait
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

class HeldEscalationTest {
    private val due = Instant.parse("2026-04-30T16:00:00Z")
    private val five = Duration.ofDays(5)
    private val atMargin = Instant.parse("2026-04-25T16:00:00Z")
    private val nano = Duration.ofNanos(1)
    private val held = listOf(HOLD_CODE)

    private fun decide(
        state: LifecycleState = LifecycleState.HELD,
        reasons: List<String> = held,
        dueAt: Instant? = due,
        margin: Duration = five,
        asOf: Instant = atMargin,
    ) = HeldEscalation.decide(HeldEscalationInput(state, reasons, dueAt, margin, asOf))

    @Test
    fun `INV-AC-7129 escalates at due minus margin, waits one nanosecond before, escalates later`() {
        decide() shouldBe Escalate(EscalationReason.MARGIN_REACHED)
        decide(asOf = atMargin.minus(nano)) shouldBe Wait
        decide(asOf = atMargin.plus(Duration.ofDays(1))) shouldBe Escalate(EscalationReason.MARGIN_REACHED)
        decide(state = LifecycleState.INGESTED) shouldBe Escalate(EscalationReason.MARGIN_REACHED)
    }

    @Test
    fun `INV-AC-7129 margin zero escalates at the due date and not a nanosecond before`() {
        decide(margin = Duration.ZERO, asOf = due) shouldBe Escalate(EscalationReason.MARGIN_REACHED)
        decide(margin = Duration.ZERO, asOf = due.minus(nano)) shouldBe Wait
    }

    @Test
    fun `INV-AC-7129 unknown due date escalates at once in HELD and in INGESTED`() {
        decide(dueAt = null) shouldBe Escalate(EscalationReason.NO_DUE_DATE)
        decide(state = LifecycleState.INGESTED, dueAt = null) shouldBe Escalate(EscalationReason.NO_DUE_DATE)
    }

    @Test
    fun `INV-AC-7129 every other state waits past the margin`() {
        val others = LifecycleState.entries.filter { it != LifecycleState.INGESTED && it != LifecycleState.HELD }
        others.size shouldBe 16
        others.forEach { decide(state = it) shouldBe Wait }
    }

    @Test
    fun `INV-AC-7129 only the exact hold code ING-016 covers a document`() {
        decide(reasons = listOf("PER-011")) shouldBe Wait
        decide(reasons = emptyList()) shouldBe Wait
        decide(reasons = listOf("PER-011", "ING-016")) shouldBe Escalate(EscalationReason.MARGIN_REACHED)
        listOf("ing-016", "ING-0160", "XING-016", "ING-016 ", "ING-01").forEach {
            decide(reasons = listOf(it)) shouldBe Wait
        }
    }

    @Test
    fun `INV-AC-7129 a document that is not covered waits even with an unknown due date`() {
        decide(reasons = listOf("PER-011"), dueAt = null) shouldBe Wait
        decide(state = LifecycleState.QUEUED, dueAt = null) shouldBe Wait
    }

    @Test
    fun `INV-AC-7129 a negative margin is refused`() {
        assertThrows<IllegalArgumentException> { decide(margin = Duration.ofSeconds(-1)) }
        assertThrows<IllegalArgumentException> { decide(margin = Duration.ofNanos(-1)) }
    }

    @Test
    fun `INV-AC-7129 the comparison does not overflow at the extremes of Instant`() {
        val asOf = Instant.parse("2026-04-25T00:00:00Z")
        val day = Duration.ofDays(1)
        decide(dueAt = Instant.MAX, margin = day, asOf = asOf) shouldBe Wait
        decide(dueAt = Instant.MIN.plusSeconds(10), margin = day, asOf = asOf) shouldBe
            Escalate(EscalationReason.MARGIN_REACHED)
        decide(margin = day, asOf = Instant.MAX) shouldBe Escalate(EscalationReason.MARGIN_REACHED)
        decide(margin = day, asOf = Instant.MIN) shouldBe Wait
    }

    @Test
    fun `HOLD_CODE is the catalogue code ING-016 with severity HOLD`() {
        HOLD_CODE shouldBe "ING-016"
        HOLD_CODE shouldBe ErrorCode.ING_016.code
        ErrorCode.fromCode(HOLD_CODE) shouldBe ErrorCode.ING_016
        ErrorCode.ING_016.severity shouldBe Severity.HOLD
        listOf("ING-017", "ING-016 ", "ing-016").forEach { HOLD_CODE shouldNotBe it }
    }

    @Test
    fun `EscalationReason members are exactly MARGIN_REACHED and NO_DUE_DATE`() {
        EscalationReason.entries.map { it.name } shouldContainExactlyInAnyOrder listOf("MARGIN_REACHED", "NO_DUE_DATE")
    }
}
