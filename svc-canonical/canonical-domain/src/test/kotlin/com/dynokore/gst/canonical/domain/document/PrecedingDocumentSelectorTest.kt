package com.dynokore.gst.canonical.domain.document

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** The link from a credit note to its preceding document (INV-AC-752 needs it; INV-Q-6311 option A). */
class PrecedingDocumentSelectorTest {
    private val march = LocalDate.of(2026, 3, 10)
    private val a = PrecedingCandidate(UUID.randomUUID(), march)
    private val b = PrecedingCandidate(UUID.randomUUID(), LocalDate.of(2027, 3, 10))

    @Test
    fun `INV-AC-752 one candidate and no date is resolved`() {
        PrecedingDocumentSelector.select(listOf(a), null) shouldBe PrecedingResolution.Resolved(a.docUuid)
    }

    @Test
    fun `INV-AC-752 one candidate with a matching date is resolved`() {
        PrecedingDocumentSelector.select(listOf(a), march) shouldBe PrecedingResolution.Resolved(a.docUuid)
    }

    @Test
    fun `INV-AC-752 one candidate with a date one day off is unresolved`() {
        PrecedingDocumentSelector.select(listOf(a), march.plusDays(1)) shouldBe PrecedingResolution.Unresolved
        PrecedingDocumentSelector.select(listOf(a), march.minusDays(1)) shouldBe PrecedingResolution.Unresolved
    }

    @Test
    fun `INV-AC-752 two candidates of two fiscal years and no date are unresolved`() {
        PrecedingDocumentSelector.select(listOf(a, b), null) shouldBe PrecedingResolution.Unresolved
    }

    @Test
    fun `INV-AC-752 two candidates and a date matching one resolve to that one`() {
        PrecedingDocumentSelector.select(listOf(a, b), b.transactionDate) shouldBe
            PrecedingResolution.Resolved(b.docUuid)
    }

    @Test
    fun `INV-AC-752 two candidates with the same date are unresolved`() {
        val twin = PrecedingCandidate(UUID.randomUUID(), march)
        PrecedingDocumentSelector.select(listOf(a, twin), march) shouldBe PrecedingResolution.Unresolved
    }

    @Test
    fun `INV-AC-752 no candidate is unresolved`() {
        PrecedingDocumentSelector.select(emptyList(), null) shouldBe PrecedingResolution.Unresolved
        PrecedingDocumentSelector.select(emptyList(), march) shouldBe PrecedingResolution.Unresolved
    }
}
