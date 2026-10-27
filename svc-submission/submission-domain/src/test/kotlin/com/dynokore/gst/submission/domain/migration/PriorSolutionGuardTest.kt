package com.dynokore.gst.submission.domain.migration

import com.dynokore.gst.submission.domain.migration.PriorSolutionDecision.ExternallySubmitted
import com.dynokore.gst.submission.domain.migration.PriorSolutionDecision.HoldForCorrection
import com.dynokore.gst.submission.domain.migration.PriorSolutionDecision.NotAffected
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PriorSolutionGuardTest {
    private val cutover = LocalDate.of(2026, 3, 1)
    private val before = LocalDate.of(2026, 2, 27)
    private val after = LocalDate.of(2026, 3, 5)
    private val record = ImportedPriorRecord("E-1", "INV-100", before, "ACK-1")
    private val imported = listOf(record)

    private fun doc(
        number: String? = "INV-100",
        date: LocalDate = before,
        entity: String = "E-1",
        creditNote: Boolean = false,
        precedingNumber: String? = null,
        precedingDate: LocalDate? = null,
        versions: List<LifecycleState> = emptyList(),
    ) = PriorSolutionDocument(entity, number, date, creditNote, precedingNumber, precedingDate, versions)

    private fun hold(reason: HoldReason) = HoldForCorrection("SUB-017", reason)

    private val corrected = hold(HoldReason.CORRECTION_OF_PRE_CUTOVER_DOCUMENT)

    @Test
    fun `INV-AC-2107 pre-cutover invoice matching imported evidence is externally submitted`() {
        PriorSolutionGuard.decide(doc(), cutover, imported) shouldBe ExternallySubmitted("ACK-1")
    }

    @Test
    fun `INV-AC-2107 same number dated after the cutover is not matched`() {
        PriorSolutionGuard.decide(doc(date = after), cutover, imported) shouldBe NotAffected
    }

    @Test
    fun `INV-AC-2108 credit note with a pre-cutover preceding date is held`() {
        PriorSolutionGuard.decide(
            doc(number = "CN-1", date = after, creditNote = true, precedingDate = before),
            cutover,
            imported,
        ) shouldBe
            corrected
    }

    @Test
    fun `INV-AC-2108 credit note with preceding number matching imported record is held`() {
        PriorSolutionGuard.decide(
            doc(number = "CN-1", date = after, creditNote = true, precedingNumber = "INV-100"),
            cutover,
            imported,
        ) shouldBe
            corrected
    }

    @Test
    fun `INV-AC-2108 credit note with a post-cutover preceding date is not affected`() {
        val d = doc(number = "CN-1", date = after, creditNote = true, precedingDate = LocalDate.of(2026, 3, 2))
        PriorSolutionGuard.decide(d, cutover, imported) shouldBe NotAffected
    }

    @Test
    fun `INV-AC-2108 preceding number of another entity is not held`() {
        val d = doc(number = "CN-1", date = after, entity = "E-2", creditNote = true, precedingNumber = "INV-100")
        PriorSolutionGuard.decide(d, cutover, imported) shouldBe NotAffected
    }

    @Test
    fun `INV-AC-2108 post-cutover imported record behind the preceding number is not held`() {
        val late = listOf(ImportedPriorRecord("E-1", "INV-100", cutover, "ACK-9"))
        val d = doc(number = "CN-1", date = after, creditNote = true, precedingNumber = "INV-100")
        PriorSolutionGuard.decide(d, cutover, late) shouldBe NotAffected
    }

    @Test
    fun `INV-AC-2108 preceding date present and not before the cutover wins over the preceding number`() {
        val d =
            doc(number = "CN-1", date = after, creditNote = true, precedingNumber = "INV-100", precedingDate = cutover)
        PriorSolutionGuard.decide(d, cutover, imported) shouldBe NotAffected
    }

    @Test
    fun `INV-AC-2108 debit note with a pre-cutover preceding date is not affected`() {
        PriorSolutionGuard.decide(
            doc(number = "DN-1", date = after, precedingDate = before),
            cutover,
            imported,
        ) shouldBe
            NotAffected
    }

    @Test
    fun `INV-AC-2108 credit note with the cutover date absent is not held`() {
        PriorSolutionGuard.decide(
            doc(number = "CN-1", date = after, creditNote = true, precedingDate = before),
            null,
            imported,
        ) shouldBe
            NotAffected
    }

    @Test
    fun `INV-AC-2107 transaction date equal to the cutover date is not affected`() {
        val atCutover = listOf(ImportedPriorRecord("E-1", "INV-100", cutover, "ACK-1"))
        PriorSolutionGuard.decide(doc(date = cutover), cutover, atCutover) shouldBe NotAffected
    }

    @Test
    fun `INV-AC-2107 numbers differing in one character are not matched`() {
        listOf("INV-100 ", "inv-100", "INV-0100").forEach {
            PriorSolutionGuard.decide(doc(number = it), cutover, imported) shouldBe NotAffected
        }
    }

    @Test
    fun `INV-AC-2107 another entity or another date is not matched`() {
        PriorSolutionGuard.decide(doc(entity = "E-2"), cutover, imported) shouldBe NotAffected
        PriorSolutionGuard.decide(doc(date = before.minusDays(1)), cutover, imported) shouldBe NotAffected
    }

    @Test
    fun `INV-AC-2107 cutover date absent with a matching record is not affected`() {
        PriorSolutionGuard.decide(doc(), null, imported) shouldBe NotAffected
    }

    @Test
    fun `INV-AC-2107 absent and blank document numbers are not affected`() {
        PriorSolutionGuard.decide(doc(number = null), cutover, imported) shouldBe NotAffected
        PriorSolutionGuard.decide(doc(number = "  "), cutover, imported) shouldBe NotAffected
    }

    @Test
    fun `INV-AC-2107 other version states do not stop the match`() {
        PriorSolutionGuard.decide(doc(versions = listOf(LifecycleState.ACKNOWLEDGED)), cutover, imported) shouldBe
            ExternallySubmitted("ACK-1")
    }

    @Test
    fun `INV-AC-2108 resubmission of an externally submitted document is held without a cutover date`() {
        val d = doc(versions = listOf(LifecycleState.EXTERNALLY_SUBMITTED))
        PriorSolutionGuard.decide(d, null, imported) shouldBe hold(HoldReason.RESUBMISSION_OF_EXTERNALLY_SUBMITTED)
    }

    @Test
    fun `INV-AC-2108 resubmission hold comes before the match of a pre-cutover document`() {
        val d = doc(versions = listOf(LifecycleState.EXTERNALLY_SUBMITTED))
        PriorSolutionGuard.decide(d, cutover, imported) shouldBe hold(HoldReason.RESUBMISSION_OF_EXTERNALLY_SUBMITTED)
    }

    @Test
    fun `INV-AC-2107 credit note matching rule 2 itself is externally submitted not held`() {
        val d = doc(creditNote = true, precedingDate = before)
        PriorSolutionGuard.decide(d, cutover, imported) shouldBe ExternallySubmitted("ACK-1")
    }

    @Test
    fun `INV-AC-2107 equal input gives equal output`() {
        PriorSolutionGuard.decide(doc(), cutover, imported) shouldBe
            PriorSolutionGuard.decide(doc(), cutover, imported.toList())
    }

    @Test
    fun `INV-AC-2107 duplicate key with different acknowledgement references is refused`() {
        shouldThrow<IllegalArgumentException> {
            PriorSolutionGuard.decide(doc(), cutover, imported + ImportedPriorRecord("E-1", "INV-100", before, "ACK-2"))
        }
        PriorSolutionGuard.decide(doc(), cutover, imported + record) shouldBe ExternallySubmitted("ACK-1")
    }

    @Test
    fun `INV-AC-2107 blank strings in an imported record are refused`() {
        shouldThrow<IllegalArgumentException> { ImportedPriorRecord(" ", "INV-100", before, "ACK-1") }
        shouldThrow<IllegalArgumentException> { ImportedPriorRecord("E-1", "", before, "ACK-1") }
        shouldThrow<IllegalArgumentException> { ImportedPriorRecord("E-1", "INV-100", before, " ") }
        shouldThrow<IllegalArgumentException> { doc(entity = " ") }
    }

    @Test
    fun `INV-AC-2108 the code constant is SUB-017`() {
        PriorSolutionGuard.SUB_017 shouldBe "SUB-017"
    }

    @Test
    fun `INV-AC-2108 hold reasons are the two named members`() {
        HoldReason.entries.map { it.name } shouldBe
            listOf("RESUBMISSION_OF_EXTERNALLY_SUBMITTED", "CORRECTION_OF_PRE_CUTOVER_DOCUMENT")
    }
}
