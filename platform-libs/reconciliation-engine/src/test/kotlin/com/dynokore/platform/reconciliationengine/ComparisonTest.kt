package com.dynokore.platform.reconciliationengine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ComparisonTest {
    @Test
    fun `equal sides give variance zero on every line, ordered by key`() {
        val r = Comparisons.compare(mapOf("B2" to d("5"), "B1" to d("7.50")), mapOf("B1" to d("7.50"), "B2" to d("5")))
        assertEquals(listOf("B1", "B2"), r.map { it.key })
        r.forEach {
            assertEquals(0, it.variance.signum())
            assertEquals(MissingSide.NONE, it.missingSide)
        }
    }

    @Test
    fun `a key only in the return side`() {
        val l = Comparisons.compare(mapOf("A" to d("500.00")), emptyMap()).single()
        assertEquals(d("500.00"), l.variance)
        assertEquals(MissingSide.SUBMITTED, l.missingSide)
        assertEquals(0, l.submittedAmount.signum())
    }

    @Test
    fun `a key only in the submitted side`() {
        val l = Comparisons.compare(emptyMap(), mapOf("A" to d("20.5"))).single()
        assertEquals(d("-20.5"), l.variance)
        assertEquals(MissingSide.RETURN, l.missingSide)
    }

    @Test
    fun `scale is kept`() {
        val l = Comparisons.compare(mapOf("A" to d("10.00")), mapOf("A" to d("10"))).single()
        assertEquals("0.00", l.variance.toString())
    }

    @Test
    fun `blank and over-long keys are refused on either side`() {
        listOf("", "  ", "x".repeat(65)).forEach { k ->
            assertThrows(IllegalArgumentException::class.java) { Comparisons.compare(mapOf(k to d("1")), emptyMap()) }
            assertThrows(IllegalArgumentException::class.java) { Comparisons.compare(emptyMap(), mapOf(k to d("1"))) }
        }
        Comparisons.compare(mapOf("x".repeat(64) to d("1")), emptyMap())
    }

    @Test
    fun `inputs are not modified`() {
        val a = linkedMapOf("B" to d("1"), "A" to d("2"))
        val b = linkedMapOf("C" to d("3"))
        Comparisons.compare(a, b)
        assertEquals(listOf("B", "A"), a.keys.toList())
        assertEquals(mapOf("C" to d("3")), b)
    }
}
