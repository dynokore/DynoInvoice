package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.events.ScopePackReader
import com.dynokore.gst.canonical.adapters.persistence.ScopePackRow
import com.dynokore.gst.canonical.domain.derivation.UpstreamFunction
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.util.UUID

/** INV-AC-25429: the reader of RulePackStaged and RulePackActivated (INV-REQ-772). */
class ScopePackReaderTest {
    private val reader = ScopePackReader()
    private val platform = TenantId.PLATFORM

    // A copy of rules/examples/valid/scope-pack-contents-full.json of platform-contracts (classificationTable only).
    private val fullTable =
        """
        [
          {"upstreamFunction":"ADJUSTMENT","upstreamKind":"DEBIT_NOTE","signOfTotal":"POSITIVE",
           "documentFunction":"ADJUSTMENT","documentTypeCode":"380","signNormalised":false,
           "sourceClass":"SECONDARY","sourceReference":"test data","ruleStatus":"PROVISIONAL",
           "provisionalReference":"INV-Q-1467"},
          {"upstreamFunction":"ADJUSTMENT","upstreamKind":"INVOICE","signOfTotal":"NEGATIVE",
           "documentFunction":"ADJUSTMENT","documentTypeCode":"381","signNormalised":true,
           "sourceClass":"PRIMARY","sourceReference":"test data"},
          {"upstreamFunction":"BILL_FOR_PAYMENT","upstreamKind":"PRO_FORMA","signOfTotal":"POSITIVE",
           "documentFunction":"NOT_A_BILL","signNormalised":false,"sourceClass":"PRIMARY","sourceReference":"test data"}
        ]
        """.trimIndent()

    private fun staged(
        table: String = fullTable,
        packType: String = "SCOPE",
        version: String? = "\"2026.1.0\"",
    ): String =
        "{\"packIdentifier\":\"gst-scope\",\"packType\":\"$packType\"" +
            (if (version != null) ",\"version\":$version" else "") +
            ",\"effectiveFrom\":\"2026-12-01\",\"artefact\":{\"manifest\":{},\"checksums\":{},\"signature\":\"c2ln\"," +
            "\"contents\":{\"classificationTable\":$table}}}"

    @Test
    fun `INV-AC-25429 R1 the sample classification table yields the distinct coded pairs`() {
        val pack = reader.readStaged(staged(), platform)!!
        pack.packIdentifier shouldBe "gst-scope"
        pack.version shouldBe "2026.1.0"
        pack.rows shouldBe
            setOf(
                ScopePackRow(Integer.parseInt("380"), UpstreamFunction.ADJUSTMENT),
                ScopePackRow(Integer.parseInt("381"), UpstreamFunction.ADJUSTMENT),
            )
    }

    @Test
    fun `INV-AC-25429 R2 a pack type other than SCOPE is no scope pack, including the lower case spelling`() {
        reader.readStaged(staged(packType = "CALENDAR"), platform) shouldBe null
        reader.readStaged(staged(packType = "scope"), platform) shouldBe null
    }

    @Test
    fun `INV-AC-25429 R3 a type code outside the contract pattern is a contract violation`() {
        for (bad in listOf("\"382\"", "\"3800\"", "\"380\\n\"", "380", "null", "\"\"")) {
            val table = fullTable.replace("\"documentTypeCode\":\"380\"", "\"documentTypeCode\":$bad")
            assertThrows<ContractViolation>("code $bad") { reader.readStaged(staged(table), platform) }
        }
    }

    @Test
    fun `INV-AC-25429 R4 a coded NOT_A_BILL row, an uncoded bill row and an empty table are contract violations`() {
        val coded =
            fullTable.replace(
                "\"documentFunction\":\"NOT_A_BILL\"",
                "\"documentFunction\":\"NOT_A_BILL\",\"documentTypeCode\":\"381\"",
            )
        assertThrows<ContractViolation> { reader.readStaged(staged(coded), platform) }
        val uncoded = fullTable.replace("\"documentTypeCode\":\"381\",", "")
        assertThrows<ContractViolation> { reader.readStaged(staged(uncoded), platform) }
        assertThrows<ContractViolation> { reader.readStaged(staged("[]"), platform) }
    }

    @Test
    fun `INV-AC-25429 R5 a missing version, a tenant other than the platform and bad JSON are contract violations`() {
        assertThrows<ContractViolation> { reader.readStaged(staged(version = null), platform) }
        assertThrows<ContractViolation> { reader.readStaged(staged(), TenantId(UUID.randomUUID())) }
        assertThrows<ContractViolation> { reader.readStaged("[1]", platform) }
        assertThrows<ContractViolation> { reader.readStaged("{", platform) }
    }

    @Test
    fun `INV-AC-25429 R6 an activation reads identifier, version and date and fails closed`() {
        val ok = "{\"packIdentifier\":\"gst-scope\",\"version\":\"2026.1.0\",\"effectiveFrom\":\"2026-12-01\"}"
        val activation = reader.readActivation(ok, platform)
        activation.packIdentifier shouldBe "gst-scope"
        activation.version shouldBe "2026.1.0"
        activation.effectiveFrom shouldBe LocalDate.parse("2026-12-01")
        assertThrows<ContractViolation> { reader.readActivation(ok.replace("\"version\":\"2026.1.0\",", ""), platform) }
        assertThrows<ContractViolation> { reader.readActivation(ok.replace("2026-12-01", "01/12/2026"), platform) }
        assertThrows<ContractViolation> { reader.readActivation(ok, TenantId(UUID.randomUUID())) }
    }
}
