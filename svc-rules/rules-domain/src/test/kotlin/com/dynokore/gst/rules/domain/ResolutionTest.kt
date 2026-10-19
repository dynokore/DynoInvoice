package com.dynokore.gst.rules.domain

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.domain.resolution.PackResolution
import com.dynokore.gst.rules.domain.resolution.PackResolver
import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.gst.rules.domain.resolution.ResolutionDates
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ResolutionTest {
    private val mapping =
        registryOf(
            version(PackTypes.MAPPING, "v1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1), TENANT_A),
            version(PackTypes.MAPPING, "v2", LocalDate.of(2026, 7, 1), tenant = TENANT_A),
        )

    private fun dates(
        own: LocalDate,
        preceding: LocalDate? = null,
    ) = ResolutionDates(TransactionDate(own), preceding?.let(::TransactionDate))

    @Test
    fun `INV-AC-1723 a back-dated document resolves the pack in force on its transaction date`() {
        // evaluated on 15 July, dated 20 June: the lookup takes only the document's date, so v1 is bound
        val resolved =
            PackResolver(
                mapping,
            ).resolve(listOf(PackTypes.MAPPING), TENANT_A, dates(LocalDate.of(2026, 6, 20)))
        val binding = resolved.shouldBeInstanceOf<PackResolution.Resolved>().binding
        binding.appliedFor(PackTypes.MAPPING)!!.version.value shouldBe "v1"
    }

    @Test
    fun `INV-AC-2105 a backfilled document from last year is bound to last year's pack version`() {
        val registry =
            registryOf(
                version(PackTypes.CODE_CATALOGUE, "2025.11", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1)),
                version(PackTypes.CODE_CATALOGUE, "2026.1", LocalDate.of(2026, 1, 1)),
            )
        val resolved =
            PackResolver(
                registry,
            ).resolve(listOf(PackTypes.CODE_CATALOGUE), null, dates(LocalDate.of(2025, 11, 14)))
        resolved
            .shouldBeInstanceOf<PackResolution.Resolved>()
            .binding.applied
            .single()
            .version.value shouldBe "2025.11"
    }

    @Test
    fun `INV-AC-1781 a document dated before any effective pack is blocked with RUL-002 and no default is applied`() {
        val registry = registryOf(version(PackTypes.MAPPING, "v1", LocalDate.of(2026, 7, 1), tenant = TENANT_A))
        val resolved =
            PackResolver(
                registry,
            ).resolve(listOf(PackTypes.MAPPING), TENANT_A, dates(LocalDate.of(2026, 6, 20)))
        val refusal = resolved.shouldBeInstanceOf<PackResolution.NoPackEffective>()
        refusal.code shouldBe ErrorCode.RUL_002
        refusal.packType shouldBe PackTypes.MAPPING
    }

    @Test
    fun `INV-AC-1782 a linked credit note resolves its rate at the preceding date and its scope at its own`() {
        val registry =
            registryOf(
                version(PackTypes.CODE_CATALOGUE, "rate-old", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1)),
                version(PackTypes.CODE_CATALOGUE, "rate-new", LocalDate.of(2026, 1, 1)),
                version(PackTypes.SCOPE, "scope-old", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1)),
                version(PackTypes.SCOPE, "scope-new", LocalDate.of(2026, 1, 1)),
                version(PackTypes.MDE, "mde-new", LocalDate.of(2026, 1, 1)),
                version(PackTypes.CALENDAR, "cal-new", LocalDate.of(2026, 1, 1)),
            )
        val types = listOf(PackTypes.CODE_CATALOGUE, PackTypes.SCOPE, PackTypes.MDE, PackTypes.CALENDAR)
        val binding =
            PackResolver(registry)
                .resolve(types, null, dates(LocalDate.of(2026, 1, 10), LocalDate.of(2025, 12, 20)))
                .shouldBeInstanceOf<PackResolution.Resolved>()
                .binding
        binding.appliedFor(PackTypes.CODE_CATALOGUE)!!.version.value shouldBe "rate-old"
        binding.appliedFor(PackTypes.SCOPE)!!.version.value shouldBe "scope-new"
        binding.appliedFor(PackTypes.MDE)!!.version.value shouldBe "mde-new"
        binding.appliedFor(PackTypes.CALENDAR)!!.version.value shouldBe "cal-new"
    }

    @Test
    fun `INV-AC-1902 a regulatory activation leaves the tenant's approved mapping pack unchanged`() {
        val registry = registryOf(version(PackTypes.MAPPING, "v1", LocalDate.of(2026, 1, 1), tenant = TENANT_A))
        val before =
            PackResolver(
                registry,
            ).resolve(listOf(PackTypes.MAPPING, PackTypes.SCOPE), TENANT_A, dates(LocalDate.of(2026, 2, 1)))
        before.shouldBeInstanceOf<PackResolution.NoPackEffective>().packType shouldBe PackTypes.SCOPE
        registry.register(version(PackTypes.SCOPE, "2026.2", LocalDate.of(2026, 2, 1)))
        val after =
            PackResolver(
                registry,
            ).resolve(listOf(PackTypes.MAPPING, PackTypes.SCOPE), TENANT_A, dates(LocalDate.of(2026, 2, 1)))
                .shouldBeInstanceOf<PackResolution.Resolved>()
                .binding
        // both versions are recorded on a later document, and the mapping pack keeps its version
        after.appliedFor(PackTypes.MAPPING)!!.version.value shouldBe "v1"
        after.appliedFor(PackTypes.SCOPE)!!.version.value shouldBe "2026.2"
    }
}
