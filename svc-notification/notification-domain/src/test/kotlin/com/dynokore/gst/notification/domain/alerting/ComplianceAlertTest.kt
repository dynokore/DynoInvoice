package com.dynokore.gst.notification.domain.alerting

import com.dynokore.gst.notification.domain.alerting.ComplianceAlertDecision.Raise
import com.dynokore.gst.notification.domain.alerting.ComplianceAlertDecision.Withhold
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ComplianceAlertTest {
    private fun values(
        docs: Int = 1,
        days: Int? = null,
    ) = ComplianceValues("ENT-1", "2026-Q3", LocalDate.of(2026, 10, 29), docs, 0, days)

    private val tiers = listOf(PolicyTier(1, 7), PolicyTier(2, 3), PolicyTier(3, 0))

    private fun coverage() = ComplianceAlertDraft(AlertClass.COVERAGE, "COVERAGE_GAP", 1, "ENT-1", "2026-Q3")

    private fun deadline(
        tier: Int,
        trigger: String = "DUE_DATE_THRESHOLD",
    ) = ComplianceAlertDraft(AlertClass.DEADLINE, trigger, tier, "ENT-1", "2026-Q3")

    @Test
    fun `INV-AC-12838 zero documents without submission is withheld`() {
        ComplianceAlert.decide(values(docs = 0, days = 3), tiers) shouldBe Withhold(WithholdReason.NOTHING_TO_ALERT)
    }

    @Test
    fun `INV-AC-12838 documents and no days give exactly the coverage draft`() {
        ComplianceAlert.decide(values(docs = 2), tiers) shouldBe Raise(listOf(coverage()))
    }

    @Test
    fun `INV-AC-12838 tier follows days to the nearest deadline`() {
        ComplianceAlert.decide(values(days = 10), tiers) shouldBe Raise(listOf(coverage(), deadline(1)))
        ComplianceAlert.decide(values(days = 3), tiers) shouldBe Raise(listOf(coverage(), deadline(2)))
        ComplianceAlert.decide(values(days = 0), tiers) shouldBe Raise(listOf(coverage(), deadline(3)))
        ComplianceAlert.decide(values(days = -2), tiers) shouldBe
            Raise(listOf(coverage(), deadline(3, "PAST_DUE")))
    }

    @Test
    fun `INV-AC-12838 the same values with other tiers give other tiers`() {
        ComplianceAlert.decide(values(days = 10), listOf(PolicyTier(1, 30), PolicyTier(2, 14))) shouldBe
            Raise(listOf(coverage(), deadline(2)))
    }

    @Test
    fun `INV-AC-12838 tiers given unsorted give the highest qualifying tier`() {
        ComplianceAlert.decide(values(days = 0), listOf(PolicyTier(1, 7), PolicyTier(3, 0), PolicyTier(2, 3))) shouldBe
            Raise(listOf(coverage(), deadline(3)))
        ComplianceAlert.decide(values(days = 0), listOf(PolicyTier(3, 0), PolicyTier(1, 7), PolicyTier(2, 3))) shouldBe
            Raise(listOf(coverage(), deadline(3)))
    }

    @Test
    fun `INV-AC-12838 a tier with null daysToDue is ignored`() {
        ComplianceAlert.decide(values(days = 3), listOf(PolicyTier(1, 7), PolicyTier(2, 3), PolicyTier(5, null))) shouldBe
            Raise(listOf(coverage(), deadline(2)))
    }

    @Test
    fun `INV-AC-12838 an empty tier list gives tier 1`() {
        ComplianceAlert.decide(values(days = 3), emptyList()) shouldBe Raise(listOf(coverage(), deadline(1)))
    }

    @Test
    fun `INV-AC-12838 day 0 is not past due and the coverage draft comes first`() {
        val d = ComplianceAlert.decide(values(days = 0), tiers) as Raise
        d.drafts.map { it.alertClass } shouldBe listOf(AlertClass.COVERAGE, AlertClass.DEADLINE)
        d.drafts[1].trigger shouldBe TRIGGER_DUE_DATE_THRESHOLD
    }

    @Test
    fun `INV-AC-12838 invalid values are refused`() {
        shouldThrow<IllegalArgumentException> { values(docs = -1) }
        shouldThrow<IllegalArgumentException> { ComplianceValues(" ", "p", LocalDate.of(2026, 1, 1), 1, 0, null) }
        shouldThrow<IllegalArgumentException> { ComplianceValues("e", "", LocalDate.of(2026, 1, 1), 1, 0, null) }
        shouldThrow<IllegalArgumentException> { ComplianceValues("e", "p", LocalDate.of(2026, 1, 1), 1, -1, null) }
    }
}
