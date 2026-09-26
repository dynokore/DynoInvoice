package com.dynokore.platform.evidencestore

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class ValueTypesTest {
    @Test
    fun `EntityRef refuses a blank value`() {
        assertThrows(IllegalArgumentException::class.java) { EntityRef("") }
        assertThrows(IllegalArgumentException::class.java) { EntityRef("  ") }
    }

    @Test
    fun `PeriodRef refuses a blank value`() {
        assertThrows(IllegalArgumentException::class.java) { PeriodRef("") }
    }

    @Test
    fun `ObjectKey refuses a blank value`() {
        assertThrows(IllegalArgumentException::class.java) { ObjectKey("") }
    }

    @Test
    fun `LegalHold refuses a blank reason`() {
        assertThrows(IllegalArgumentException::class.java) { LegalHold("", Instant.now()) }
    }
}
