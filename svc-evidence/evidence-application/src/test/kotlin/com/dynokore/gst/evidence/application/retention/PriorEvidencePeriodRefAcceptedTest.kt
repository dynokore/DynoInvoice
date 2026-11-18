package com.dynokore.gst.evidence.application.retention

import com.dynokore.gst.evidence.domain.retention.PriorEvidencePeriodRef
import com.dynokore.platform.evidencestore.PeriodRef
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PriorEvidencePeriodRefAcceptedTest {
    private fun accepted(date: LocalDate) {
        val result = PriorEvidencePeriodRef.of(date)
        PeriodRef(result).value shouldBe result
    }

    @Test
    fun `INV-AC-24791 PeriodRef accepts the result for each month of 2026`() {
        (1..12).forEach { accepted(LocalDate.of(2026, it, 1)) }
    }

    @Test
    fun `INV-AC-24791 PeriodRef accepts the result for the first and last representable years`() {
        accepted(LocalDate.of(999, 2, 1))
        accepted(LocalDate.of(9999, 12, 31))
    }
}
