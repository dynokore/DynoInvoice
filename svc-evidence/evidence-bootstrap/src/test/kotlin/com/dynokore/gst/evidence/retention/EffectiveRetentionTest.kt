package com.dynokore.gst.evidence.retention

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import com.dynokore.gst.evidence.domain.retention.RetentionTier.ACKNOWLEDGEMENTS_AND_TRACES as ACK
import com.dynokore.gst.evidence.domain.retention.RetentionTier.SOURCE_DOCUMENT_REFERENCES as SOURCE
import com.dynokore.gst.evidence.domain.retention.RetentionTier.STRUCTURED_SUBMISSIONS as STRUCTURED

/**
 * Resolution of the retention per tier through the service boundary: `setPolicy` over HTTP against the real
 * service-runtime wiring and PostgreSQL, then [com.dynokore.gst.evidence.application.retention.EffectiveRetention]
 * read in the tenant's request (INV-REQ-638 b1).
 */
class EffectiveRetentionTest {
    private val baseline = "baseline: the catalogue default"
    private val parent = "parent: DE, HGB §257, 7 years"

    private fun <T> serve(
        dir: Path,
        block: (RetentionService) -> T,
    ): T = RetentionService.start(dir).use(block)

    @Test
    fun `INV-AC-2390 an entity override gives two entities different retention`(
        @TempDir dir: Path,
    ): Unit =
        serve(dir) { s ->
            val tenant = s.newTenant()
            s.setTenant(tenant, mapOf(STRUCTURED to 5, ACK to 5, SOURCE to 5), baseline).accepted()
            s.setEntity(tenant, "ENT-A", mapOf(STRUCTURED to 7, ACK to 5, SOURCE to 5), parent).accepted()
            s.years(tenant, "ENT-A", STRUCTURED) shouldBe 7
            s.years(tenant, "ENT-B", STRUCTURED) shouldBe 5
            val refused = s.setEntity(tenant, "ENT-B", mapOf(STRUCTURED to 4, ACK to 5, SOURCE to 5), "shorter")
            refused.code shouldBe "POL-013"
            s.years(tenant, "ENT-B", STRUCTURED) shouldBe 5
        }

    @Test
    fun `INV-AC-10451 a 4-year value, an unexplained 7 and a 9 with a reason resolve per tier`(
        @TempDir dir: Path,
    ): Unit =
        serve(dir) { s ->
            val tenant = s.newTenant()
            s.setTenant(tenant, mapOf(STRUCTURED to 5, ACK to 5, SOURCE to 5), baseline).accepted()
            s.setEntity(tenant, "ENT-A", mapOf(STRUCTURED to 7, ACK to 5, SOURCE to 5), parent).accepted()
            s.years(tenant, "ENT-A") shouldBe listOf(7, 5, 5)
            s.years(tenant, "ENT-B") shouldBe listOf(5, 5, 5)
            val stored = s.stored(tenant)

            val below = s.setEntity(tenant, "ENT-B", mapOf(STRUCTURED to 4, ACK to 5, SOURCE to 5), "shorter")
            below.code shouldBe "POL-013"
            s.years(tenant, "ENT-B") shouldBe listOf(5, 5, 5)

            val unexplained = s.setTenant(tenant, mapOf(STRUCTURED to 7, ACK to 5, SOURCE to 5), null)
            unexplained.code shouldBe "POL-005"
            s.stored(tenant) shouldBe stored

            s.setTenant(tenant, mapOf(STRUCTURED to 5, ACK to 9, SOURCE to 5), "obligation of the parent").accepted()
            s.years(tenant, "ENT-A") shouldBe listOf(7, 5, 5)
            s.years(tenant, "ENT-B") shouldBe listOf(5, 9, 5)
        }

    @Test
    fun `INV-AC-10452 retention resolves per tier as entity value, else tenant value, else default`(
        @TempDir dir: Path,
    ): Unit =
        serve(dir) { s ->
            val tenant = s.newTenant()
            s.setTenant(tenant, mapOf(STRUCTURED to 5, ACK to 9, SOURCE to 5), "obligation of the parent").accepted()
            s.setEntity(tenant, "ENT-A", mapOf(STRUCTURED to 5, ACK to 5, SOURCE to 5), baseline).accepted()
            s.setEntity(tenant, "ENT-B", mapOf(STRUCTURED to 7, ACK to 7, SOURCE to 7), parent).accepted()
            s.years(tenant, "ENT-A") shouldBe listOf(5, 5, 5)
            s.years(tenant, "ENT-B") shouldBe listOf(7, 7, 7)
            s.years(tenant, "ENT-C") shouldBe listOf(5, 9, 5)
            s.years(s.newTenant(), "ENT-A") shouldBe listOf(5, 5, 5)
        }

    @Test
    fun `INV-AC-10453 each tier defaults to the catalogue default and a longer one needs a reason`(
        @TempDir dir: Path,
    ): Unit =
        serve(dir) { s ->
            val none = s.newTenant()
            s.years(none, "ENT-A") shouldBe listOf(5, 5, 5)
            val tenant = s.newTenant()
            s.setTenant(tenant, mapOf(STRUCTURED to 5, ACK to 5, SOURCE to 7), null).code shouldBe "POL-005"
            s.stored(tenant) shouldBe 0
            val reason = "HGB §257 obligation of the parent"
            val accepted = s.setTenant(tenant, mapOf(STRUCTURED to 5, ACK to 5, SOURCE to 7), reason)
            accepted.accepted()
            accepted.body.get("reason").asString() shouldBe reason
            s.reasons(tenant) shouldBe listOf(reason)
            s.years(tenant, "ENT-A") shouldBe listOf(5, 5, 7)
        }
}
