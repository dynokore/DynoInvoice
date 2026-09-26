package com.dynokore.platform.evidencestore

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RetentionGateTest {
    private val periodEnd = LocalDate.of(2026, 3, 31)
    private val retention = RetentionPeriod.DEFAULT // 5 years

    @Test
    fun `refuses deletion before retention ends (INV-REQ-635)`() {
        assertFalse(RetentionGate.canDelete(retention, periodEnd, periodEnd.plusYears(4), held = false))
    }

    @Test
    fun `allows deletion the moment retention ends`() {
        assertTrue(RetentionGate.canDelete(retention, periodEnd, periodEnd.plusYears(5), held = false))
    }

    @Test
    fun `a legal hold always refuses deletion, however long retention has run (FR-D_4)`() {
        assertFalse(RetentionGate.canDelete(retention, periodEnd, periodEnd.plusYears(50), held = true))
    }
}
