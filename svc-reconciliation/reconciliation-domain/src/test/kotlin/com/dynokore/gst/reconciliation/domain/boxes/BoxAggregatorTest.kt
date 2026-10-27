package com.dynokore.gst.reconciliation.domain.boxes

import com.dynokore.gst.reconciliation.domain.sgd.SgdLine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class BoxAggregatorTest {
    private fun d(s: String) = BigDecimal(s)

    private fun feed(
        box: String,
        basis: Basis,
    ) = BoxFeed(box, basis)

    private val entries =
        listOf(
            MappingEntry("SR", Direction.SALE, listOf(feed("1", Basis.TAXABLE_AMOUNT), feed("6", Basis.TAX_AMOUNT))),
            MappingEntry(
                "SR",
                Direction.PURCHASE,
                listOf(feed("5", Basis.TAXABLE_AMOUNT), feed("7", Basis.TAX_AMOUNT)),
            ),
            MappingEntry("ZR", Direction.SALE, listOf(feed("2", Basis.TAXABLE_AMOUNT))),
        )

    private fun line(
        doc: String,
        code: String,
        direction: Direction,
        amounts: Pair<String, String>,
        at: Pair<Int, Int> = 1 to 1,
    ) = DocumentLine(doc, at.second, direction, SgdLine(at.first, code, d(amounts.first), d(amounts.second)))

    private val standardLines =
        listOf(
            line("A", "SR", Direction.SALE, "100.00" to "9.00"),
            line("B", "SR", Direction.SALE, "-50.00" to "-4.50"),
            line("A", "ZR", Direction.SALE, "30.00" to "0.00", at = 2 to 1),
            line("C", "SR", Direction.PURCHASE, "20.00" to "1.80"),
        )

    @Test
    fun `INV-AC-677 lines aggregate per Box with sign and no unmapped line`() {
        val result = BoxAggregator.aggregate(entries, standardLines)
        result.boxes shouldBe
            mapOf("1" to d("50.00"), "2" to d("30.00"), "5" to d("20.00"), "6" to d("4.50"), "7" to d("1.80"))
        result.unmappedLines shouldBe emptyList()
    }

    @Test
    fun `INV-AC-677 unmapped lines are returned and add to no Box`() {
        val extra =
            listOf(
                line("D", "XX", Direction.SALE, "10.00" to "1.00", at = 3 to 1),
                line("D", "sr", Direction.SALE, "10.00" to "1.00", at = 2 to 1),
                line("D", "ZR", Direction.PURCHASE, "10.00" to "0.00", at = 1 to 1),
                line("C", " SR", Direction.SALE, "10.00" to "1.00", at = 5 to 2),
            )
        val result = BoxAggregator.aggregate(entries, standardLines + extra)
        result.boxes shouldBe BoxAggregator.aggregate(entries, standardLines).boxes
        result.unmappedLines shouldBe
            listOf(
                UnmappedLine("C", 2, 5, " SR"),
                UnmappedLine("D", 1, 1, "ZR"),
                UnmappedLine("D", 1, 2, "sr"),
                UnmappedLine("D", 1, 3, "XX"),
            )
    }

    @Test
    fun `INV-AC-677 the direction decides the entry`() {
        val result = BoxAggregator.aggregate(entries, listOf(line("A", "SR", Direction.PURCHASE, "20.00" to "1.80")))
        result.boxes shouldBe mapOf("5" to d("20.00"), "7" to d("1.80"))
    }

    @Test
    fun `INV-AC-677 submitted versions are the highest acknowledged and unsubmitted the highest of the rest`() {
        val states =
            listOf(
                VersionState("A", 1, true),
                VersionState("A", 2, false),
                VersionState("B", 1, true),
                VersionState("B", 2, true),
                VersionState("C", 1, false),
                VersionState("D", 3, true),
                VersionState("E", 1, false),
                VersionState("E", 2, false),
            )
        BoxAggregator.submittedVersions(states) shouldBe mapOf("A" to 1, "B" to 2, "D" to 3)
        BoxAggregator.unsubmittedCurrentVersions(states) shouldBe mapOf("C" to 1, "E" to 2)
        BoxAggregator.submittedVersions(states.reversed()) shouldBe mapOf("A" to 1, "B" to 2, "D" to 3)
    }

    @Test
    fun `INV-AC-677 a duplicated pair fails`() {
        shouldThrow<IllegalArgumentException> { BoxAggregator.aggregate(entries + entries.first(), emptyList()) }
    }

    @Test
    fun `INV-AC-677 a duplicated box in one entry fails`() {
        val bad =
            MappingEntry("SR", Direction.SALE, listOf(feed("1", Basis.TAXABLE_AMOUNT), feed("1", Basis.TAX_AMOUNT)))
        shouldThrow<IllegalArgumentException> { BoxAggregator.aggregate(listOf(bad), emptyList()) }
    }

    @Test
    fun `INV-AC-677 amounts on one Box add at scale 2`() {
        val result =
            BoxAggregator.aggregate(
                entries,
                listOf(
                    line("A", "ZR", Direction.SALE, "0.10" to "0"),
                    line("B", "ZR", Direction.SALE, "0.2" to "0"),
                ),
            )
        result.boxes["2"] shouldBe d("0.30")
        result.boxes.getValue("2").scale() shouldBe 2
    }

    @Test
    fun `INV-AC-677 an amount with three decimal places fails`() {
        shouldThrow<IllegalArgumentException> {
            BoxAggregator.aggregate(entries, listOf(line("A", "ZR", Direction.SALE, "1.005" to "0.00")))
        }
    }

    @Test
    fun `INV-AC-677 the result does not depend on input order`() {
        val extra = listOf(line("D", "XX", Direction.SALE, "1.00" to "0.10", at = 9 to 1))
        val forward = BoxAggregator.aggregate(entries, standardLines + extra)
        val shuffled = BoxAggregator.aggregate(entries.reversed(), (standardLines + extra).reversed())
        shuffled shouldBe forward
    }

    @Test
    fun `INV-AC-677 no lines give an empty map and no unmapped line`() {
        BoxAggregator.aggregate(entries, emptyList()) shouldBe BoxAggregation(emptyMap(), emptyList())
    }
}
