package com.dynokore.gst.ingestion.domain.triggers

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

class TriggerCoverageDecisionTest {
    private val day = LocalDate.of(2027, 1, 31)

    private fun record(c: TriggerClass): Pair<TriggerClass, CoverageRecord> = c to CoverageRecord.Mapped("erp-ar")

    @Test
    fun `INV-AC-20934 mapping with 1 character adapterRef is accepted`() {
        TriggerCoverageDecision.mapped("a").adapterRef shouldBe "a"
    }

    @Test
    fun `INV-AC-20934 mapping with 40 character adapterRef is accepted`() {
        TriggerCoverageDecision.mapped("a".repeat(40)).adapterRef.length shouldBe 40
    }

    @Test
    fun `INV-AC-20934 mapping with 41 character adapterRef is refused`() {
        assertThrows<IllegalArgumentException> { TriggerCoverageDecision.mapped("a".repeat(41)) }
    }

    @Test
    fun `INV-AC-20934 mapping with blank adapterRef is refused`() {
        assertThrows<IllegalArgumentException> { TriggerCoverageDecision.mapped("") }
        assertThrows<IllegalArgumentException> { TriggerCoverageDecision.mapped("   ") }
    }

    @Test
    fun `INV-AC-20934 review waiver with a review date is accepted`() {
        TriggerCoverageDecision.waived(day).reviewDate shouldBe day
    }

    @Test
    fun `INV-AC-20934 review waiver without a review date is refused`() {
        assertThrows<IllegalArgumentException> { TriggerCoverageDecision.waived(null) }
    }

    @Test
    fun `INV-AC-20934 covered elsewhere with solution and evidence is accepted`() {
        val r = TriggerCoverageDecision.coveredElsewhere("s".repeat(200), "e".repeat(200))
        r.coveringSolution.length shouldBe 200
    }

    @Test
    fun `INV-AC-20934 covered elsewhere with blank covering solution is refused`() {
        assertThrows<IllegalArgumentException> { TriggerCoverageDecision.coveredElsewhere(" ", "ev") }
    }

    @Test
    fun `INV-AC-20934 covered elsewhere with blank evidence reference is refused`() {
        assertThrows<IllegalArgumentException> { TriggerCoverageDecision.coveredElsewhere("sol", "") }
    }

    @Test
    fun `INV-AC-20934 covered elsewhere members over 200 characters are refused`() {
        assertThrows<IllegalArgumentException> { TriggerCoverageDecision.coveredElsewhere("s".repeat(201), "ev") }
        assertThrows<IllegalArgumentException> { TriggerCoverageDecision.coveredElsewhere("sol", "e".repeat(201)) }
    }

    @Test
    fun `INV-AC-20934 class name outside the five is refused`() {
        assertThrows<IllegalArgumentException> { TriggerCoverageDecision.classOf("OTHER") }
        assertThrows<IllegalArgumentException> { TriggerCoverageDecision.classOf("journal_only_supply") }
        assertThrows<IllegalArgumentException> { TriggerCoverageDecision.classOf("") }
    }

    @Test
    fun `INV-AC-20934 the five contract names are accepted`() {
        TriggerClass.entries.map { it.name } shouldBe
            listOf(
                "OUTBOUND_DOCUMENT_ISSUED",
                "INBOUND_DOCUMENT_RECORDED",
                "AGGREGABLE_BATCH_CLOSED",
                "JOURNAL_ONLY_SUPPLY",
                "THIRD_PARTY_ISSUANCE",
            )
        TriggerClass.entries.forEach { TriggerCoverageDecision.classOf(it.name) shouldBe it }
    }

    @Test
    fun `INV-AC-20934 four records name exactly the missing class`() {
        val four = TriggerClass.entries.filter { it != TriggerClass.AGGREGABLE_BATCH_CLOSED }.associate { record(it) }
        TriggerCoverageDecision.missing(four) shouldBe listOf(TriggerClass.AGGREGABLE_BATCH_CLOSED)
    }

    @Test
    fun `INV-AC-20934 each single missing member is named`() {
        TriggerClass.entries.forEach { gone ->
            val four = TriggerClass.entries.filter { it != gone }.associate { record(it) }
            TriggerCoverageDecision.missing(four) shouldBe listOf(gone)
        }
    }

    @Test
    fun `INV-AC-20934 five records are complete`() {
        TriggerCoverageDecision.missing(TriggerClass.entries.associate { record(it) }) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20934 zero records name all five in declaration order`() {
        TriggerCoverageDecision.missing(emptyMap()) shouldBe TriggerClass.entries.toList()
    }
}
