package com.dynokore.gst.rules.domain

import com.dynokore.gst.rules.domain.pack.MappingAcceptance
import com.dynokore.gst.rules.domain.pack.MappingAcceptanceRecord
import com.dynokore.gst.rules.domain.pack.MappingDraftEntry
import com.dynokore.gst.rules.domain.pack.SourceClass
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class MappingAcceptanceTest {
    private val asOf = LocalDate.of(2026, 11, 2)
    private val x = MappingDraftEntry("X", "SR", SourceClass.SECONDARY)
    private val y = MappingDraftEntry("Y", "SR", SourceClass.SECONDARY)

    private fun acc(
        entry: MappingDraftEntry,
        rationale: String = "reviewed against R1",
        expiresOn: LocalDate = asOf.plusDays(1),
    ) = MappingAcceptanceRecord(entry.entryId, rationale, expiresOn, entry.irasCode, entry.sourceClass, "u-1")

    private fun accepted(
        entries: List<MappingDraftEntry>,
        vararg acceptances: MappingAcceptanceRecord,
    ) = MappingAcceptance.acceptedEntryIds(entries, acceptances.toList(), asOf)

    @Test
    fun `INV-AC-756 an acceptance expiring on asOf no longer counts but the day after does`() {
        accepted(listOf(x), acc(x, expiresOn = asOf)) shouldBe emptySet()
        accepted(listOf(x), acc(x, expiresOn = asOf.minusDays(1))) shouldBe emptySet()
        accepted(listOf(x), acc(x, expiresOn = asOf.plusDays(1))) shouldBe setOf("X")
    }

    @Test
    fun `INV-AC-756 a blank rationale does not accept`() {
        accepted(listOf(x), acc(x, rationale = "")) shouldBe emptySet()
        accepted(listOf(x), acc(x, rationale = "   ")) shouldBe emptySet()
    }

    @Test
    fun `INV-AC-756 a 501-character rationale does not accept and 500 does`() {
        accepted(listOf(x), acc(x, rationale = "a".repeat(501))) shouldBe emptySet()
        accepted(listOf(x), acc(x, rationale = "a".repeat(500))) shouldBe setOf("X")
    }

    @Test
    fun `INV-AC-756 an acceptance of entry X does not accept entry Y with the same IRAS code`() {
        accepted(listOf(x, y), acc(x)) shouldBe setOf("X")
    }

    @Test
    fun `INV-AC-756 an entry changed to another IRAS code or source class after the acceptance is not in the set`() {
        val a = acc(x)
        accepted(listOf(x.copy(irasCode = "ZR")), a) shouldBe emptySet()
        accepted(listOf(x.copy(sourceClass = SourceClass.INFERRED)), a) shouldBe emptySet()
    }

    @Test
    fun `INV-AC-756 a PRIMARY entry is never in the set`() {
        val p = MappingDraftEntry("P", "SR", SourceClass.PRIMARY)
        accepted(listOf(p), acc(p)) shouldBe emptySet()
    }

    @Test
    fun `INV-AC-756 an acceptance of an unknown entry id is ignored`() {
        accepted(listOf(x), acc(y)) shouldBe emptySet()
    }
}
