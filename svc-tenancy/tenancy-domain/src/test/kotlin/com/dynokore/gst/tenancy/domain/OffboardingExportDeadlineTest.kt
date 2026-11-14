package com.dynokore.gst.tenancy.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** The export deadline rule (INV-AC-22959). */
class OffboardingExportDeadlineTest {
    private val created = Instant.parse("2026-11-14T10:00:00Z")
    private val limit = Duration.ofHours(24)

    @Test
    fun `INV-AC-22959 at exactly the limit the record is not overdue and one second later it is`() {
        assertFalse(exportOverdue(OffboardingState.REQUESTED, created, created.plus(limit), limit))
        assertTrue(exportOverdue(OffboardingState.REQUESTED, created, created.plus(limit).plusSeconds(1), limit))
    }

    @Test
    fun `INV-AC-22959 a COMPLETED or FAILED record is never overdue`() {
        val late = created.plus(limit).plusSeconds(1)
        assertFalse(exportOverdue(OffboardingState.COMPLETED, created, late, limit))
        assertFalse(exportOverdue(OffboardingState.FAILED, created, late, limit))
    }
}
