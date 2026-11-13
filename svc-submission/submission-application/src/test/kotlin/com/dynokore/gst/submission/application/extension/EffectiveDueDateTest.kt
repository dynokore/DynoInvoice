package com.dynokore.gst.submission.application.extension

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The effective due date resolver, pure (INV-AC-20599). */
class EffectiveDueDateTest {
    private val register = LocalDate.of(2026, 4, 30)
    private val d1 = "11111111-1111-1111-1111-111111111111"
    private val d2 = "22222222-2222-2222-2222-222222222222"
    private val d3 = "33333333-3333-3333-3333-333333333333"
    private val tx1 = LocalDate.of(2026, 3, 10)
    private val tx2 = LocalDate.of(2026, 3, 20)

    private fun row(
        kind: String,
        ref: String?,
        date: String,
        target: String = "SUBMISSION_DUE_DATE",
    ) = ExtensionInput(target, kind, ref, LocalDate.parse(date))

    private val period = row("PERIOD", null, "2026-05-14")
    private val range = row("DATE_RANGE", "2026-03-15/2026-03-31", "2026-06-01")
    private val set = row("DOCUMENT_SET", d1, "2026-05-20")
    private val filing = row("PERIOD", null, "2026-05-31", target = "FILING_DUE_DATE")
    private val laterPeriod = row("PERIOD", null, "2026-05-16")
    private val all = listOf(period, range, set, filing, laterPeriod)

    private fun effective(
        doc: String,
        tx: LocalDate?,
        rows: List<ExtensionInput> = all,
    ) = EffectiveDueDate.resolve(register, doc, tx, rows)

    @Test
    fun `INV-AC-20599 the document set is the narrowest scope`() {
        effective(d1, tx1).dueDate shouldBe LocalDate.of(2026, 5, 20)
    }

    @Test
    fun `INV-AC-20599 the date range is narrower than the period`() {
        effective(d2, tx2).dueDate shouldBe LocalDate.of(2026, 6, 1)
    }

    @Test
    fun `INV-AC-20599 a document outside every narrower scope gets the latest recorded period row`() {
        val other = "44444444-4444-4444-4444-444444444444"
        effective(other, LocalDate.of(2026, 4, 2)).dueDate shouldBe LocalDate.of(2026, 5, 16)
    }

    @Test
    fun `INV-AC-20599 two period rows give the latest recorded whichever date is later`() {
        effective(
            d3,
            null,
            listOf(row("PERIOD", null, "2026-05-16"), row("PERIOD", null, "2026-05-14")),
        ).dueDate shouldBe
            LocalDate.of(2026, 5, 14)
    }

    @Test
    fun `INV-AC-20599 a filing due date row only gives the register date`() {
        effective(d3, tx1, listOf(filing)).dueDate shouldBe register
        effective(d1, tx1, listOf(row("DOCUMENT_SET", d1, "2026-05-31", target = "FILING_DUE_DATE"))).dueDate shouldBe
            register
    }

    @Test
    fun `INV-AC-20599 a both row gives its submission date`() {
        effective(d3, tx1, listOf(row("PERIOD", null, "2026-05-14", target = "BOTH"))).dueDate shouldBe
            LocalDate.of(2026, 5, 14)
    }

    @Test
    fun `INV-AC-20599 a date range bound is inclusive at both ends and the day outside gets the register date`() {
        val rows = listOf(range)
        effective(d3, LocalDate.of(2026, 3, 15), rows).dueDate shouldBe LocalDate.of(2026, 6, 1)
        effective(d3, LocalDate.of(2026, 3, 31), rows).dueDate shouldBe LocalDate.of(2026, 6, 1)
        effective(d3, LocalDate.of(2026, 3, 14), rows).dueDate shouldBe register
        effective(d3, LocalDate.of(2026, 4, 1), rows).dueDate shouldBe register
        effective(d3, null, rows).dueDate shouldBe register
    }

    @Test
    fun `INV-AC-20599 no rows gives the register date`() {
        effective(d1, tx1, emptyList()).dueDate shouldBe register
        effective(d1, tx1, emptyList()).unorderable.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-20599 the look-alike scope kind DATE-RANGE is reported and not matched`() {
        val lookAlike = row("DATE-RANGE", "2026-03-15/2026-03-31", "2026-06-01")
        val result = effective(d2, tx2, listOf(lookAlike))
        result.dueDate shouldBe register
        result.unorderable shouldBe listOf(lookAlike)
    }

    @Test
    fun `INV-AC-20599 an unreadable range or a missing reference is reported and not matched`() {
        val bad = listOf(row("DATE_RANGE", "2026-03-15", "2026-06-01"), row("DOCUMENT_SET", null, "2026-06-02"))
        val result = effective(d2, tx2, bad)
        result.dueDate shouldBe register
        result.unorderable shouldBe bad
    }
}
