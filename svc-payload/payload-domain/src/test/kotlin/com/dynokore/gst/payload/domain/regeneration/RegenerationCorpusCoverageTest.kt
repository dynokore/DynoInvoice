package com.dynokore.gst.payload.domain.regeneration

import com.dynokore.gst.payload.domain.regeneration.CorpusCoverage.Covered
import com.dynokore.gst.payload.domain.regeneration.CorpusCoverage.NotCovered
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RegenerationCorpusCoverageTest {
    private val change = setOf(LocalDate.parse("2024-01-01"))

    private fun entry(
        id: String = "cn-1",
        function: CorpusDocumentFunction = CorpusDocumentFunction.CREDIT_NOTE,
        issued: String = "2024-02-01",
        preceding: String? = "2023-12-01",
        packs: Map<String, String> = mapOf("peppol" to "v1"),
    ) = CorpusEntry(id, function, LocalDate.parse(issued), preceding?.let { LocalDate.parse(it) }, packs)

    private fun gap(
        e: CorpusEntry,
        dates: Set<LocalDate> = change,
    ) {
        RegenerationCorpusCoverage.check(listOf(e), dates) shouldBe NotCovered(CoverageGap.NO_STRADDLING_CREDIT_NOTE)
    }

    @Test
    fun `INV-AC-14097 straddling credit note is covered`() {
        RegenerationCorpusCoverage.check(listOf(entry()), change) shouldBe Covered(listOf("cn-1"))
    }

    @Test
    fun `INV-AC-14097 issue on the change date is covered`() {
        RegenerationCorpusCoverage.check(listOf(entry(issued = "2024-01-01")), change) shouldBe Covered(listOf("cn-1"))
    }

    @Test
    fun `INV-AC-14097 empty corpus is not covered`() {
        RegenerationCorpusCoverage.check(emptyList(), change) shouldBe NotCovered(CoverageGap.EMPTY_CORPUS)
    }

    @Test
    fun `INV-AC-14097 empty corpus with no dates is EMPTY_CORPUS first`() {
        RegenerationCorpusCoverage.check(emptyList(), emptySet()) shouldBe NotCovered(CoverageGap.EMPTY_CORPUS)
    }

    @Test
    fun `INV-AC-14097 no rate change date is not covered`() {
        RegenerationCorpusCoverage.check(listOf(entry()), emptySet()) shouldBe
            NotCovered(CoverageGap.NO_RATE_CHANGE_KNOWN)
    }

    @Test
    fun `INV-AC-14097 credit note issued before the change`() = gap(entry(issued = "2023-12-31"))

    @Test
    fun `INV-AC-14097 preceding on the change date`() = gap(entry(preceding = "2024-01-01"))

    @Test
    fun `INV-AC-14097 preceding after the change date`() = gap(entry(preceding = "2024-03-01"))

    @Test
    fun `INV-AC-14097 no preceding date`() = gap(entry(preceding = null))

    @Test
    fun `INV-AC-14097 empty pack versions`() = gap(entry(packs = emptyMap()))

    @Test
    fun `INV-AC-14097 other function with straddling dates`() = gap(entry(function = CorpusDocumentFunction.OTHER))

    @Test
    fun `INV-AC-14097 covered lists only the straddling id in order`() {
        val list = listOf(entry(id = "a", issued = "2023-12-31"), entry(id = "b"))
        RegenerationCorpusCoverage.check(list, change) shouldBe Covered(listOf("b"))
    }

    @Test
    fun `INV-AC-14097 note between two changes does not straddle`() {
        val dates = setOf(LocalDate.parse("2023-01-01"), LocalDate.parse("2024-01-01"))
        gap(entry(preceding = "2023-06-01", issued = "2023-07-01"), dates)
    }

    @Test
    fun `gap enum is closed`() {
        CoverageGap.entries.map { it.name } shouldBe
            listOf("EMPTY_CORPUS", "NO_RATE_CHANGE_KNOWN", "NO_STRADDLING_CREDIT_NOTE")
    }
}
