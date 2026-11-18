package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.application.document.AppliedPackFact
import com.dynokore.gst.canonical.application.document.ContentLine
import com.dynokore.gst.canonical.application.document.DocumentContent
import com.dynokore.gst.canonical.application.document.LineTaxFact
import com.dynokore.gst.canonical.application.document.PartyIdentifier
import com.dynokore.gst.canonical.application.document.PeriodSpans
import com.dynokore.gst.canonical.application.document.RecordDocumentCommand
import com.dynokore.gst.canonical.application.document.ScopeAnswer
import com.dynokore.gst.canonical.application.document.ScopeAnswerCapture
import com.dynokore.gst.canonical.application.document.ScopeEvaluation
import com.dynokore.gst.canonical.domain.dating.PeriodSpan
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.DocumentState
import com.dynokore.gst.canonical.domain.document.ScopeVerdict
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class DocumentRowFactsFromRecordTest {
    private val tenant = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val entityId = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
    private val periodId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    private val docUuid = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val writer = CanonicalDocumentWriter()
    private val packs =
        listOf(AppliedPackFact("SCOPE", "gst-scope", "S-2026.1"), AppliedPackFact("MAPPING", "gst-map", "M-3"))
    private val verdict =
        ScopeAnswer.Verdict(
            scopeVerdict = "IN_SCOPE",
            documentTypeCode = "380",
            scopePackVersion = "S-2026.1",
            mappingPackVersion = "M-3",
            lineTax = listOf(LineTaxFact("SR", "9.00")),
            appliedPacks = packs,
        )
    private val calls = mutableListOf<String>()

    private fun complete() =
        DocumentContent(
            "SGD",
            null,
            null,
            listOf(ContentLine(1, "item", BigDecimal("2"), "50.00", "100.00", null, null)),
            null,
        )

    /** A line without description: the writer refuses it, so a writer call is visible as RowFactUnknown(canonical). */
    private fun incomplete() =
        DocumentContent("SGD", null, null, listOf(ContentLine(1, null, null, null, "1.00", null, null)), null)

    private fun command(
        direction: Direction = Direction.SALE,
        supplier: PartyIdentifier = PartyIdentifier("SG_UEN", "123"),
        content: DocumentContent? = complete(),
    ) = RecordDocumentCommand(
        tenantId = tenant,
        entityRef = "E-1",
        sourceSystem = "erp",
        sourceKey = "k1",
        sourceRevision = 1,
        transactionDate = LocalDate.parse("2026-03-20"),
        direction = direction,
        supplier = supplier,
        buyer = PartyIdentifier("SG_UEN", "BUYER"),
        invoiceBacked = true,
        content = content,
        documentNumber = "N-1",
    )

    private fun result(
        date: String = "2026-03-20",
        period: String = "2026-Q1",
        reasons: String = "",
    ) = """{"transactionDate":"$date","prescribedPeriodRef":"$period","scopeVerdict":"IN_SCOPE","reasons":[$reasons]}"""

    private val held = "\"PER_009\""

    private fun capture(v: ScopeAnswer.Verdict? = verdict): ScopeAnswerCapture {
        val c = ScopeAnswerCapture(ScopeEvaluation { _, _, _ -> v ?: ScopeAnswer.Unavailable })
        return c
    }

    private fun source(
        capture: ScopeAnswerCapture,
        entity: UUID? = entityId,
        partyRef: String? = "P-9",
    ) = DocumentRowFactsFromRecord(
        entityIdOf = { _, _ ->
            calls += "entityIdOf"
            entity
        },
        spansOf =
            PeriodSpans { _, _ ->
                calls += "spansOf"
                listOf(PeriodSpan("2026-Q1", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31")))
            },
        periodIdOf = { _, _, _ ->
            calls += "periodIdOf"
            periodId
        },
        partyRefOf = {
            calls += "partyRefOf"
            partyRef
        },
        capture = capture,
        writer = writer,
        actingUser = { "U-7" },
    )

    private fun captured(
        cmd: RecordDocumentCommand,
        v: ScopeAnswer.Verdict? = verdict,
    ): ScopeAnswerCapture {
        val c = capture(v)
        c.evaluate(cmd, null, cmd.transactionDate)
        return c
    }

    @Test
    fun `INV-AC-21604 S1 a proceeding SALE record fills the row from the result, the capture and the lookups`() {
        val cmd = command()
        val f = source(captured(cmd)).factsOf(cmd, docUuid, 1, result())!!
        f.entityId shouldBe entityId
        f.state shouldBe DocumentState.INGESTED
        f.version.scopeVerdict shouldBe ScopeVerdict.IN_SCOPE
        f.version.scopePackVersion shouldBe "S-2026.1"
        f.version.mappingPackVersion shouldBe "M-3"
        f.version.mdePackVersion shouldBe null
        f.version.specificationVersion shouldBe null
        f.documentTypeCode shouldBe 380.toShort()
        f.version.transactionDate shouldBe LocalDate.parse("2026-03-20")
        f.fiscalYear shouldBe 2026.toShort()
        f.prescribedPeriodId shouldBe periodId
        f.version.createdBy shouldBe "U-7"
        f.version.canonical shouldBe writer.write(cmd.content!!, "380", verdict.lineTax)
        f.businessUnitId shouldBe null
        f.internalReference shouldBe null
    }

    @Test
    fun `INV-AC-21604 S2 a held record has state HELD and no scope verdict, pack versions as captured`() {
        val cmd = command()
        val f = source(captured(cmd)).factsOf(cmd, docUuid, 1, result(reasons = held))!!
        f.state shouldBe DocumentState.HELD
        f.version.scopeVerdict shouldBe null
        f.version.scopePackVersion shouldBe "S-2026.1"
        f.version.mappingPackVersion shouldBe "M-3"
    }

    @Test
    fun `INV-AC-21604 S3 the capture is taken once by factsOf`() {
        val cmd = command()
        val c = captured(cmd)
        source(c).factsOf(cmd, docUuid, 1, result())
        c.take(cmd) shouldBe null
    }

    @Test
    fun `INV-AC-21604 S4 an unassigned period has no period id and the year of the transaction date`() {
        val cmd = command()
        val f = source(captured(cmd)).factsOf(cmd, docUuid, 1, result(date = "2026-04-02", period = ""))!!
        f.prescribedPeriodId shouldBe null
        f.fiscalYear shouldBe 2026.toShort()
        ("periodIdOf" in calls) shouldBe false
    }

    @Test
    fun `INV-AC-21604 S4 the fiscal year is the year of the span end, not of the transaction date`() {
        val cmd = command()
        val s =
            DocumentRowFactsFromRecord(
                { _, _ -> entityId },
                { _, _ -> listOf(PeriodSpan("FY", LocalDate.parse("2025-11-01"), LocalDate.parse("2026-10-31"))) },
                { _, _, _ -> periodId },
                { "P-9" },
                captured(cmd),
                writer,
                { "U-7" },
            )
        s.factsOf(cmd, docUuid, 1, result(date = "2025-12-01", period = "FY"))!!.fiscalYear shouldBe 2026.toShort()
    }

    @Test
    fun `INV-AC-21604 S5 a PURCHASE takes the issuer key from the party reference`() {
        val cmd = command(Direction.PURCHASE)
        val f = source(captured(cmd)).factsOf(cmd, docUuid, 1, result())!!
        f.issuerPartyKey shouldBe "P-9"
    }

    @Test
    fun `INV-AC-21604 S6 a SALE takes the issuer key from the supplier identifier and never asks the party ref`() {
        val cmd = command(supplier = PartyIdentifier("SG_UEN", "200812345A"))
        val f = source(captured(cmd)).factsOf(cmd, docUuid, 1, result())!!
        f.issuerPartyKey shouldBe "SG_UEN:200812345A"
        ("partyRefOf" in calls) shouldBe false
    }

    @Test
    fun `INV-AC-21604 S7 createdBy is the acting user ID`() {
        val cmd = command()
        val f = source(captured(cmd)).factsOf(cmd, docUuid, 1, result())!!
        f.version.createdBy shouldBe "U-7"
        (f.version.createdBy in setOf("ADMIN", "OPERATOR", "SYSTEM", "ROLE_USER")) shouldBe false
    }

    @Test
    fun `INV-AC-21604 S8 an unknown entity id throws RowFactUnknown and nothing else is called`() {
        val cmd = command(content = incomplete())
        val e = assertThrows<RowFactUnknown> { source(captured(cmd), entity = null).factsOf(cmd, docUuid, 1, result()) }
        e.fact shouldBe "entityId"
        calls shouldBe listOf("entityIdOf")
    }

    @Test
    fun `INV-AC-21604 S9 a PURCHASE with an unknown party reference throws RowFactUnknown and no writer call`() {
        val cmd = command(Direction.PURCHASE, content = incomplete())
        val e =
            assertThrows<RowFactUnknown> { source(captured(cmd), partyRef = null).factsOf(cmd, docUuid, 1, result()) }
        e.fact shouldBe "issuerPartyKey"
        // the party reference is the last lookup: neither a later lookup nor the writer ran after it
        calls.last() shouldBe "partyRefOf"
    }

    @Test
    fun `INV-AC-21604 S11 a held record with no capture answers null and calls nothing`() {
        val cmd = command(Direction.PURCHASE, content = incomplete())
        val f = source(capture(null)).factsOf(cmd, docUuid, 1, result(reasons = held))
        f shouldBe null
        calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21604 S12 a record that is not held and has no capture throws RowFactUnknown scopeAnswer`() {
        val cmd = command(content = incomplete())
        val e = assertThrows<RowFactUnknown> { source(capture(null)).factsOf(cmd, docUuid, 1, result()) }
        e.fact shouldBe "scopeAnswer"
        calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21604 a type code that is absent or not a number throws RowFactUnknown documentTypeCode`() {
        val cmd = command()
        listOf(null, "abc").forEach {
            val c = captured(cmd, verdict.copy(documentTypeCode = it))
            assertThrows<RowFactUnknown> { source(c).factsOf(cmd, docUuid, 1, result()) }.fact shouldBe
                "documentTypeCode"
        }
    }

    @Test
    fun `INV-AC-21604 a scope verdict outside the three values of a proceeding record throws`() {
        val cmd = command()
        val c = captured(cmd, verdict.copy(scopeVerdict = "MAYBE"))
        assertThrows<RowFactUnknown> { source(c).factsOf(cmd, docUuid, 1, result()) }.fact shouldBe "scopeVerdict"
    }

    @Test
    fun `INV-AC-21604 an incomplete canonical document is rethrown as RowFactUnknown canonical`() {
        val cmd = command(content = incomplete())
        assertThrows<RowFactUnknown> { source(captured(cmd)).factsOf(cmd, docUuid, 1, result()) }.fact shouldBe
            "canonical"
    }

    @Test
    fun `INV-AC-21604 a null command content throws RowFactUnknown canonical and calls no writer`() {
        val cmd = command(content = null)
        val e = assertThrows<RowFactUnknown> { source(captured(cmd)).factsOf(cmd, docUuid, 1, result()) }
        e.fact shouldBe "canonical"
        e.cause shouldBe null // not the IncompleteCanonicalDocument of a writer call
    }

    @Test
    fun `INV-AC-21604 a period ref with no span throws RowFactUnknown fiscalYear`() {
        val cmd = command()
        val s =
            DocumentRowFactsFromRecord(
                { _, _ -> entityId },
                { _, _ -> listOf(PeriodSpan("2025-Q4", LocalDate.parse("2025-10-01"), LocalDate.parse("2025-12-31"))) },
                { _, _, _ -> periodId },
                { "P-9" },
                captured(cmd),
                writer,
                { "U-7" },
            )
        assertThrows<RowFactUnknown> { s.factsOf(cmd, docUuid, 1, result()) }.fact shouldBe "fiscalYear"
    }

    @Test
    fun `INV-AC-21604 a null direction throws RowFactUnknown direction`() {
        val cmd = command().copy(direction = null)
        assertThrows<RowFactUnknown> { source(captured(cmd)).factsOf(cmd, docUuid, 1, result()) }.fact shouldBe
            "direction"
    }

    @Test
    fun `INV-AC-22905 the period ref of the result is given unchanged and the applied packs in order`() {
        val cmd = command()
        val a = source(captured(cmd)).factsOf(cmd, docUuid, 1, result())!!
        a.prescribedPeriodRef shouldBe "2026-Q1"
        a.prescribedPeriodId shouldBe periodId
        a.appliedPackVersions shouldBe packs
        val b = source(captured(cmd)).factsOf(cmd, docUuid, 1, result(date = "2026-04-02", period = ""))!!
        b.prescribedPeriodRef shouldBe ""
        b.prescribedPeriodId shouldBe null
        b.appliedPackVersions shouldBe packs
    }
}
