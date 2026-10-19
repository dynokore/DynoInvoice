package com.dynokore.gst.rules.domain

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.domain.pack.EntryCheck
import com.dynokore.gst.rules.domain.pack.MappingSignOff
import com.dynokore.gst.rules.domain.pack.PackAuthority
import com.dynokore.gst.rules.domain.pack.PackEntry
import com.dynokore.gst.rules.domain.pack.PackEntryCompleteness
import com.dynokore.gst.rules.domain.pack.PackLayer
import com.dynokore.gst.rules.domain.pack.PackRecord
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.gst.rules.domain.pack.SourceClass
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PackLifecycleTest {
    private val primary = PackEntry("ENT-9", "R1 para 4.2", SourceClass.PRIMARY)

    private fun armedV3(): PackRecord =
        PackRecord.draft("MAPPING", "v3", PackLayer.TENANT_MAPPING, "tenant-a", LocalDate.of(2026, 1, 1), listOf(primary))
            .let { it.restoredAs(PackStatus.APPROVED) }
            .arm()

    private fun PackRecord.restoredAs(status: PackStatus) =
        PackRecord.restored(packType, version, layer, tenant, status, effectiveFrom, effectiveTo, supersedes, entries)

    @Test
    fun `INV-AC-1726 an armed pack refuses edits and the correction is a new superseding version`() {
        val v3 = armedV3()
        v3.edit(listOf(primary.copy(citation = "changed"))).shouldBeInstanceOf<PackRecord.Edit.Refused>()
        val v4 = v3.correct("v4", LocalDate.of(2026, 4, 1), listOf(primary.copy(citation = "changed")))
        v4.supersedes shouldBe "v3"
        v4.effectiveFrom shouldBe LocalDate.of(2026, 4, 1)
        v4.status shouldBe PackStatus.DRAFT
        // v3 is untouched and stays as it was, resolvable for earlier dates
        v3.status shouldBe PackStatus.ARMED
        v3.entries.single().citation shouldBe "R1 para 4.2"
    }

    @Test
    fun `INV-AC-1727 a scope pack entry without a source citation refuses the pack`() {
        val missing = PackEntry("ENT-2", null, SourceClass.PRIMARY)
        val refused = PackEntryCompleteness.check(PackAuthority.IRAS, listOf(primary, missing)).shouldBeInstanceOf<EntryCheck.Refused>()
        refused.findings.map { it.entryId } shouldContainExactly listOf("ENT-2")
        val noClass = PackEntry("ENT-3", "R1 para 5", null)
        PackEntryCompleteness.check(PackAuthority.IRAS, listOf(noClass)).shouldBeInstanceOf<EntryCheck.Refused>()
        val accepted = PackEntryCompleteness.check(PackAuthority.IRAS, listOf(primary)).shouldBeInstanceOf<EntryCheck.Accepted>()
        accepted.authority shouldBe PackAuthority.IRAS
        accepted.entries.single().sourceClass shouldBe SourceClass.PRIMARY
    }

    @Test
    fun `INV-AC-755 signing off a mapping pack with a SECONDARY entry is blocked with RUL-007`() {
        val secondary = PackEntry("ENT-8", null, SourceClass.SECONDARY)
        val blocked = MappingSignOff.attempt(listOf(primary, secondary)).shouldBeInstanceOf<MappingSignOff.Outcome.Blocked>()
        blocked.code shouldBe ErrorCode.RUL_007
        blocked.entryIds shouldContainExactly listOf("ENT-8")
        // re-sourced, or explicitly accepted, the sign-off goes through
        MappingSignOff.attempt(listOf(primary, secondary.copy(sourceClass = SourceClass.PRIMARY))) shouldBe MappingSignOff.Outcome.SignedOff
        MappingSignOff.attempt(listOf(primary, secondary), acceptedEntryIds = setOf("ENT-8")) shouldBe MappingSignOff.Outcome.SignedOff
    }

    @Test
    fun `an entry without a class blocks sign-off and is never read as PRIMARY`() {
        val unclassified = PackEntry("ENT-1", null, null)
        MappingSignOff.attempt(listOf(unclassified)).shouldBeInstanceOf<MappingSignOff.Outcome.Blocked>()
    }
}
