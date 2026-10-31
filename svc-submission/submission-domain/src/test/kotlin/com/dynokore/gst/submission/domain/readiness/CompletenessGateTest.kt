package com.dynokore.gst.submission.domain.readiness

import com.dynokore.gst.catalogue.Disposition
import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.catalogue.Severity
import com.dynokore.gst.submission.domain.readiness.CompletenessState.Failed
import com.dynokore.gst.submission.domain.readiness.CompletenessState.Passed
import com.dynokore.gst.submission.domain.readiness.CompletenessState.Unavailable
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CompletenessGateTest {
    private val counts = Counts(12, 12, 0, 0, 0, 0, 0)
    private val ready: ReadinessResult = ReadinessResult.Ready(counts, emptyList())
    private val unable: ReadinessResult = ReadinessResult.UnableToDetermine

    private fun blocked(n: Int): ReadinessResult =
        ReadinessResult.Blocked(n, Counts(n, 0, n, 0, 0, n, n), emptyList())

    @Test
    fun `INV-AC-1848 a failed control blocks a ready period and PER-002 override is refused`() {
        val verdict = CompletenessGate.decide(ready, Failed(listOf("C1")))
        verdict shouldBe PeriodVerdict.NotReady(setOf(BlockCode.PER_002), 0, listOf("C1"))
        CompletenessGate.override(verdict, BlockCode.PER_002) shouldBe
            OverrideOutcome.Refused(BlockCode.PER_002, Disposition.MANUAL)
        BlockCode.PER_002.code shouldBe "PER-002"
        ErrorCode.PER_002.severity shouldBe Severity.BLOCK
        ErrorCode.PER_002.disposition shouldBe Disposition.MANUAL
    }

    @Test
    fun `INV-AC-1848 codes are exactly PER-002 and the PER-001 override of the same decision is allowed`() {
        val both = CompletenessGate.decide(blocked(1), Failed(listOf("C1")))
        CompletenessGate.override(both, BlockCode.PER_001) shouldBe OverrideOutcome.Allowed
        val only = CompletenessGate.decide(ready, Failed(listOf("C1")))
        (only as PeriodVerdict.NotReady).codes shouldBe setOf(BlockCode.PER_002)
        CompletenessGate.override(only, BlockCode.PER_002) shouldBe
            OverrideOutcome.Refused(BlockCode.PER_002, ErrorCode.PER_002.disposition)
    }

    @Test
    fun `INV-AC-1848 blocked readiness and two failed controls give both codes`() {
        val verdict = CompletenessGate.decide(blocked(2), Failed(listOf("C1", "C2")))
        verdict shouldBe PeriodVerdict.NotReady(setOf(BlockCode.PER_001, BlockCode.PER_002), 2, listOf("C1", "C2"))
        CompletenessGate.override(verdict, BlockCode.PER_001) shouldBe OverrideOutcome.Allowed
        CompletenessGate.override(verdict, BlockCode.PER_002) shouldBe
            OverrideOutcome.Refused(BlockCode.PER_002, Disposition.MANUAL)
    }

    @Test
    fun `INV-AC-1848 blocked readiness with passed completeness gives only PER-001`() {
        CompletenessGate.decide(blocked(3), Passed) shouldBe
            PeriodVerdict.NotReady(setOf(BlockCode.PER_001), 3, emptyList())
    }

    @Test
    fun `INV-AC-1848 ready and passed gives ready`() {
        CompletenessGate.decide(ready, Passed) shouldBe PeriodVerdict.Ready
    }

    @Test
    fun `INV-AC-1848 an undeterminable input never gives ready`() {
        CompletenessGate.decide(unable, Failed(listOf("C1"))) shouldBe PeriodVerdict.UnableToDetermine
        CompletenessGate.decide(unable, Passed) shouldBe PeriodVerdict.UnableToDetermine
        CompletenessGate.decide(ready, Unavailable) shouldBe PeriodVerdict.UnableToDetermine
        CompletenessGate.decide(blocked(1), Unavailable) shouldBe PeriodVerdict.UnableToDetermine
    }

    @Test
    fun `INV-AC-1848 nothing to override when the verdict lacks the code`() {
        CompletenessGate.override(PeriodVerdict.Ready, BlockCode.PER_002) shouldBe OverrideOutcome.NothingToOverride
        CompletenessGate.override(PeriodVerdict.UnableToDetermine, BlockCode.PER_001) shouldBe
            OverrideOutcome.NothingToOverride
        val onlyOne = PeriodVerdict.NotReady(setOf(BlockCode.PER_001), 1, emptyList())
        CompletenessGate.override(onlyOne, BlockCode.PER_002) shouldBe OverrideOutcome.NothingToOverride
    }

    @Test
    fun `INV-AC-1848 the override outcome follows the catalogue disposition`() {
        ErrorCode.PER_001.disposition shouldBe Disposition.OVERRIDABLE
        BlockCode.entries.forEach { it.code shouldBe ErrorCode.valueOf(it.name).code }
        val all = PeriodVerdict.NotReady(BlockCode.entries.toSet(), 1, listOf("C1"))
        BlockCode.entries.forEach {
            val outcome = CompletenessGate.override(all, it)
            val disposition = ErrorCode.fromCode(it.code).disposition
            val expected =
                if (disposition == Disposition.OVERRIDABLE) OverrideOutcome.Allowed else OverrideOutcome.Refused(it, disposition)
            outcome shouldBe expected
        }
    }

    @Test
    fun `INV-AC-1848 an empty, blank or repeated control list is refused`() {
        assertThrows<IllegalArgumentException> { Failed(emptyList()) }
        assertThrows<IllegalArgumentException> { Failed(listOf("C1", " ")) }
        assertThrows<IllegalArgumentException> { Failed(listOf("C1", "C1")) }
    }
}
