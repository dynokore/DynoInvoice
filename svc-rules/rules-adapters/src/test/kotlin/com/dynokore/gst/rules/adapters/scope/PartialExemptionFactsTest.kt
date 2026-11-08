package com.dynokore.gst.rules.adapters.scope

import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.gst.rules.domain.scope.ExemptElection
import com.dynokore.platform.serviceruntime.replica.EntityProfileChanged
import com.dynokore.platform.serviceruntime.replica.ProfileEntry
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The recorded partial-exemption status and election, read from the structure replica by transaction date. */
class PartialExemptionFactsTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC)
    private val day = LocalDate.of(2027, 1, 1)

    private fun facts(
        vararg entries: Pair<String, String>,
        effective: LocalDate = day,
        entity: String = "E1",
    ): ProfileScopeFacts {
        val replica = StructureReplica(clock)
        replica.accept(
            EntityProfileChanged(
                tenant,
                entity,
                StructureNode(
                    StructureNodeKind.ENTITY,
                    null,
                    false,
                    "E",
                    entity,
                    entries.map { ProfileEntry(it.first, it.second, effective) },
                    1,
                ),
            ),
        )
        return ProfileScopeFacts(replica, clock)
    }

    private fun on(d: LocalDate = day) = TransactionDate(d)

    @Test
    fun `INV-AC-1656 only the exact value true is partially exempt, every other value is not`() {
        facts("partiallyExempt" to "true").exemptionOf(tenant, "E1", on()).partiallyExempt shouldBe true
        listOf("True", "true ", "1", "false", "", "yes", "TRUE").forEach {
            facts("partiallyExempt" to it).exemptionOf(tenant, "E1", on()).partiallyExempt shouldBe false
        }
        facts().exemptionOf(tenant, "E1", on()).partiallyExempt shouldBe false
        facts("partiallyExempt" to "true").exemptionOf(tenant, "E2", on()).partiallyExempt shouldBe false
        facts("partiallyExempt" to "true").exemptionOf(tenant, "E1", on(day.minusDays(1))).partiallyExempt shouldBe
            false
        facts("PartiallyExempt" to "true").exemptionOf(tenant, "E1", on()).partiallyExempt shouldBe false
    }

    @Test
    fun `INV-AC-399 a recorded election with codes and a basis is read in force on the transaction date`() {
        val f = facts("exemptPurchaseCodes" to "TX-N33,TX-N34", "exemptPurchaseBasis" to "certain")
        f.exemptionOf(tenant, "E1", on()).election shouldBe ExemptElection(setOf("TX-N33", "TX-N34"), "certain")
    }

    @Test
    fun `INV-AC-1645 no election, a blank basis, a future entry or another entity gives none`() {
        facts().exemptionOf(tenant, "E1", on()).election shouldBe null
        facts("exemptPurchaseCodes" to "TX-N33").exemptionOf(tenant, "E1", on()).election shouldBe null
        facts("exemptPurchaseCodes" to "TX-N33", "exemptPurchaseBasis" to "  ")
            .exemptionOf(tenant, "E1", on())
            .election shouldBe null
        facts("exemptPurchaseBasis" to "b").exemptionOf(tenant, "E1", on()).election shouldBe null
        listOf("TX-N33,", ",TX-N33", "TX-N33,,TX-N34", "").forEach {
            facts("exemptPurchaseCodes" to it, "exemptPurchaseBasis" to "b")
                .exemptionOf(tenant, "E1", on())
                .election shouldBe null
        }
        val both = arrayOf("exemptPurchaseCodes" to "TX-N33", "exemptPurchaseBasis" to "b")
        facts(*both).exemptionOf(tenant, "E1", on(day.minusDays(1))).election shouldBe null
        facts(*both).exemptionOf(tenant, "E2", on()).election shouldBe null
        // codes are kept as written: a look-alike with a trailing space names a different code
        val padded = facts("exemptPurchaseCodes" to "TX-N33 ", "exemptPurchaseBasis" to "b")
        padded.exemptionOf(tenant, "E1", on()).election?.names("TX-N33") shouldBe false
        padded.exemptionOf(tenant, "E1", on()).election?.names("tx-n33") shouldBe false
    }
}
