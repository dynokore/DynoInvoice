package com.dynokore.platform.configresolver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class BusinessDateTest {
    @Test
    fun `the business date is Singapore's, not UTC's (INV-Q-2940)`() {
        // 16:30 UTC on 30 Sep is 00:30 on 1 Oct in Singapore.
        val clock = Clock.fixed(Instant.parse("2026-09-30T16:30:00Z"), ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 10, 1), BusinessDate.today(clock))
    }
}
