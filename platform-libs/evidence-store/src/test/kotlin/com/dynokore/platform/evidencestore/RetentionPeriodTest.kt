package com.dynokore.platform.evidencestore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RetentionPeriodTest {
    @Test
    fun `default is five years`() {
        assertEquals(5, RetentionPeriod.DEFAULT.years)
    }

    @Test
    fun `a value below five years is never accepted (INV-Q-1798)`() {
        assertThrows(IllegalArgumentException::class.java) { RetentionPeriod(4) }
        assertThrows(IllegalArgumentException::class.java) { RetentionPeriod(0) }
    }

    @Test
    fun `a longer value is accepted`() {
        assertEquals(7, RetentionPeriod(7).years)
    }
}
