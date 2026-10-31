package com.dynokore.gst.evidence.retention

import com.dynokore.gst.evidence.domain.retention.RetentionTier
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The refusals of VP-43 through `setPolicy` at TENANT and at ENTITY scope: below five years POL-013, a longer value
 * without a reason POL-005, nothing stored in either case; with a reason the value is accepted and the stored change
 * carries the reason; a record without a tier, or with a look-alike field, is POL-013.
 */
class RetentionRefusalTest {
    private val reason = "HGB §257 obligation of the parent"

    private fun all(
        tier: RetentionTier,
        years: Int,
    ): Map<RetentionTier, Number> = RetentionTier.entries.associateWith { if (it == tier) years else 5 }

    private fun at(
        s: RetentionService,
        level: String,
        tenant: com.dynokore.platform.tenancykernel.TenantId,
        years: Map<RetentionTier, Number>,
        reason: String?,
    ) = if (level == "TENANT") s.setTenant(tenant, years, reason) else s.setEntity(tenant, "ENT-A", years, reason)

    @Test
    fun `INV-AC-1713 INV-AC-1919 INV-AC-2291 4 years is refused with POL-013`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            for (level in listOf("TENANT", "ENTITY")) {
                for (tier in RetentionTier.entries) {
                    val tenant = s.newTenant()
                    at(s, level, tenant, all(tier, 4), reason).code shouldBe "POL-013"
                    s.stored(tenant) shouldBe 0
                }
            }
        }

    /** (level, years, tier) of every longer value the criteria name: 7 at both scopes, 10 at entity scope. */
    private val longer: List<Triple<String, Int, RetentionTier>> =
        listOf("TENANT" to 7, "ENTITY" to 7, "ENTITY" to 10).flatMap { (level, years) ->
            RetentionTier.entries.map { Triple(level, years, it) }
        }

    @Test
    fun `INV-AC-1713 INV-AC-1919 INV-AC-2291 no reason is refused with POL-005`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            for ((level, years, tier) in longer) {
                for (missing in listOf(null, "")) {
                    val tenant = s.newTenant()
                    at(s, level, tenant, all(tier, years), missing).code shouldBe "POL-005"
                    s.stored(tenant) shouldBe 0
                }
            }
        }

    @Test
    fun `INV-AC-1713 INV-AC-1919 INV-AC-2291 a reason makes it accepted`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            for ((level, years, tier) in longer) {
                val tenant = s.newTenant()
                val answer = at(s, level, tenant, all(tier, years), reason).accepted()
                answer.body.get("reason").asString() shouldBe reason
                s.reasons(tenant) shouldBe listOf(reason)
                s.years(tenant, "ENT-A", tier) shouldBe years
                RetentionTier.entries.filter { it != tier }.forEach { s.years(tenant, "ENT-A", it) shouldBe 5 }
            }
        }

    @Test
    fun `INV-AC-1713 a missing or look-alike field is refused with POL-013`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            val missing = mapOf("structuredSubmissions" to 5, "acknowledgementsAndTraces" to 5)
            val lookAlike =
                mapOf("structuredSubmission" to 5, "acknowledgementsAndTraces" to 5, "sourceDocumentReferences" to 5)
            for (level in listOf("TENANT", "ENTITY")) {
                for (record in listOf(missing, lookAlike)) {
                    val tenant = s.newTenant()
                    s.setRaw(tenant, level, "ENT-A", record, reason).code shouldBe "POL-013"
                    s.stored(tenant) shouldBe 0
                }
            }
        }
}
