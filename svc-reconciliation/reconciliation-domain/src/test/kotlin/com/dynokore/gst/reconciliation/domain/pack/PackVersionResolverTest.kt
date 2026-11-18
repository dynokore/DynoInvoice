package com.dynokore.gst.reconciliation.domain.pack

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

class PackVersionResolverTest {
    private fun w(
        v: String,
        d: String,
    ) = PackVersionWindow(v, LocalDate.parse(d))

    private val a = w("A", "2026-01-01")
    private val b = w("B", "2026-07-01")
    private val c = w("C", "2026-10-01")

    @Test
    fun `INV-AC-24760 greatest effectiveFrom on or before the last day is chosen`() {
        val vs = listOf(a, b, c)
        PackVersionResolver.resolve(LocalDate.parse("2026-09-30"), vs) shouldBe PackVersionChoice.Chosen(b)
        PackVersionResolver.resolve(LocalDate.parse("2026-12-31"), vs) shouldBe PackVersionChoice.Chosen(c)
        PackVersionResolver.resolve(LocalDate.parse("2026-07-02"), vs) shouldBe PackVersionChoice.Chosen(b)
    }

    @Test
    fun `INV-AC-24760 effectiveFrom equal to the last day is inside the range`() {
        PackVersionResolver.resolve(LocalDate.parse("2026-10-01"), listOf(a, b, c)) shouldBe PackVersionChoice.Chosen(c)
        PackVersionResolver.resolve(LocalDate.parse("2026-01-01"), listOf(a, b, c)) shouldBe PackVersionChoice.Chosen(a)
    }

    @Test
    fun `INV-AC-24760 list order never changes the result`() {
        val day = LocalDate.parse("2026-09-30")
        val perms =
            listOf(
                listOf(a, b, c),
                listOf(c, b, a),
                listOf(b, c, a),
                listOf(a, c, b),
                listOf(c, a, b),
                listOf(b, a, c),
            )
        perms.map { PackVersionResolver.resolve(day, it) }.toSet() shouldBe setOf(PackVersionChoice.Chosen(b))
    }

    @Test
    fun `INV-AC-24760 no version covers when all are later or the list is empty`() {
        val day = LocalDate.parse("2025-12-31")
        PackVersionResolver.resolve(day, listOf(a, b, c)) shouldBe PackVersionChoice.NoVersionCovers(day)
        PackVersionResolver.resolve(day, emptyList()) shouldBe PackVersionChoice.NoVersionCovers(day)
    }

    @Test
    fun `INV-AC-24760 same effectiveFrom is refused even when neither would be chosen`() {
        assertThrows<IllegalArgumentException> {
            PackVersionResolver.resolve(LocalDate.parse("2026-09-30"), listOf(a, b, w("X", "2026-10-01"), c))
        }
        assertThrows<IllegalArgumentException> {
            PackVersionResolver.resolve(LocalDate.parse("2026-09-30"), listOf(a, w("Y", "2026-01-01")))
        }
    }

    @Test
    fun `INV-AC-24760 same packVersion is refused even when neither would be chosen`() {
        assertThrows<IllegalArgumentException> {
            PackVersionResolver.resolve(LocalDate.parse("2026-09-30"), listOf(a, b, w("C", "2026-11-01"), c))
        }
        assertThrows<IllegalArgumentException> {
            PackVersionResolver.resolve(LocalDate.parse("2026-09-30"), listOf(b, w("B", "2026-12-01")))
        }
    }

    @Test
    fun `INV-AC-24760 a later-released version with a past effectiveFrom is one more element`() {
        val late = w("L", "2026-08-01")
        val day = LocalDate.parse("2026-09-30")
        PackVersionResolver.resolve(day, listOf(a, b, c, late)) shouldBe PackVersionChoice.Chosen(late)
        PackVersionResolver.resolve(LocalDate.parse("2026-09-30"), listOf(a, b, c)) shouldBe PackVersionChoice.Chosen(b)
    }

    @Test
    fun `INV-AC-24760 identifiers differing by a trailing space are different and accepted together`() {
        val x = w("2026.1", "2026-01-01")
        val y = w("2026.1 ", "2026-07-01")
        PackVersionResolver.resolve(LocalDate.parse("2026-09-30"), listOf(x, y)) shouldBe PackVersionChoice.Chosen(y)
    }

    @Test
    fun `INV-AC-24760 a blank packVersion is refused`() {
        assertThrows<IllegalArgumentException> { w(" ", "2026-01-01") }
    }
}
