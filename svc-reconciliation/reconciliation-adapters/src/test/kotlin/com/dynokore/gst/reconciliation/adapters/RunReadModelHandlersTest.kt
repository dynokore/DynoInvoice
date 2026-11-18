package com.dynokore.gst.reconciliation.adapters

import com.dynokore.gst.reconciliation.adapters.events.CanonicalVersionCreatedHandler
import com.dynokore.gst.reconciliation.adapters.events.PeriodReassignedHandler
import com.dynokore.gst.reconciliation.adapters.events.SubmissionAcknowledgedHandler
import com.dynokore.gst.reconciliation.application.run.DocumentLineFacts
import com.dynokore.gst.reconciliation.application.run.DocumentVersionFacts
import com.dynokore.gst.reconciliation.application.run.RunReadModelWriter
import com.dynokore.gst.reconciliation.domain.boxes.Direction
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-24342 at the handlers (INV-T-24345): a fake [RunReadModelWriter] that records its calls and returns true on
 * the first call of a key and false on a repeat; the arguments, the outcomes and the contract violations are compared
 * by equality.
 */
class RunReadModelHandlersTest {
    private val tenant = UUID.randomUUID()
    private val doc = UUID.randomUUID().toString()

    private class FakeWriter : RunReadModelWriter {
        val versions = mutableListOf<Triple<String, String, DocumentVersionFacts>>()
        val reassigned = mutableListOf<List<Any>>()
        val acknowledged = mutableListOf<List<Any>>()
        private val seen = mutableSetOf<Any>()

        val calls: Int get() = versions.size + reassigned.size + acknowledged.size

        override fun recordVersion(
            tenant: String,
            entityRef: String,
            facts: DocumentVersionFacts,
        ): Boolean {
            versions += Triple(tenant, entityRef, facts)
            return seen.add(listOf("v", tenant, facts.docUuid, facts.version))
        }

        override fun reassignPeriod(
            tenant: String,
            entityRef: String,
            docUuid: String,
            docsequence: Long,
            newPeriodRef: String,
        ): Boolean {
            reassigned += listOf(tenant, entityRef, docUuid, docsequence, newPeriodRef)
            return seen.add(listOf("p", tenant, docUuid, docsequence))
        }

        override fun recordAcknowledgement(
            tenant: String,
            docUuid: String,
            version: Int,
            acknowledgementId: String,
            acknowledgedAt: Instant,
        ): Boolean {
            acknowledged += listOf(tenant, docUuid, version, acknowledgementId, acknowledgedAt)
            return seen.add(listOf("a", tenant, docUuid, version, acknowledgementId))
        }
    }

    private fun envelope(
        type: String,
        data: String,
    ) = Envelope(
        id = EventId(UUID.randomUUID().toString()),
        source = "/gst/canonical-svc",
        type = "com.dynokore.gst.canonical.$type.v1",
        subject = doc,
        time = Instant.parse("2026-11-18T01:00:00Z"),
        tenant = TenantId(tenant),
        entity = EntityRef("E1"),
        docUuid = DocUuid(UUID.fromString(doc)),
        transactionDate = LocalDate.of(2026, 2, 10),
        docSequence = 10,
        instanceId = "canonical-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun line(
        number: Int,
        amount: String,
        tax: String,
        code: String? = "SR",
    ): String {
        val taxCode = code?.let { ""","taxCode":"$it"""" }.orEmpty()
        return """{"lineNumber":$number,"lineAmount":"$amount"$taxCode,"taxAmount":"$tax"}"""
    }

    private fun created(
        routing: String? = null,
        sequence: String? = "10",
        lines: List<String> = listOf(line(1, "100.00", "9.00"), line(2, "50.50", "4.55")),
        version: String = "1",
        period: String = "2026-Q1",
    ): String {
        val extra =
            listOfNotNull(
                routing?.let { """"routingFacts":$it""" },
                sequence?.let { """"docsequence":$it""" },
            ).joinToString("") { ",$it" }
        return """{"docUuid":"$doc","version":$version,"transactionDate":"2026-02-10",""" +
            """"prescribedPeriodRef":"$period","scopeVerdict":"SUBMITTABLE"$extra,""" +
            """"canonicalDocument":{"currency":{"value":"USD","classification":"NONE"},""" +
            """"exchangeRate":{"value":"1.35000000","classification":"NONE"},""" +
            """"lines":{"value":[${lines.joinToString(",")}],"classification":"NONE"},""" +
            """"totals":{"value":{"totalExcludingTax":"150.50","totalTax":"13.55","totalIncludingTax":"164.05",""" +
            """"sgdTotalExcludingTax":"203.18","sgdTotalTax":"18.29"},"classification":"NONE"}}}"""
    }

    private fun expected(
        direction: Direction = Direction.SALE,
        lines: List<DocumentLineFacts> =
            listOf(
                DocumentLineFacts(1, "SR", BigDecimal("100.00"), BigDecimal("9.00")),
                DocumentLineFacts(2, "SR", BigDecimal("50.50"), BigDecimal("4.55")),
            ),
    ) = DocumentVersionFacts(
        docUuid = doc,
        version = 1,
        docsequence = 10,
        periodRef = "2026-Q1",
        transactionDate = LocalDate.of(2026, 2, 10),
        scopeVerdict = "SUBMITTABLE",
        notSubmittableReason = null,
        direction = direction,
        currency = "USD",
        exchangeRate = BigDecimal("1.35000000"),
        sgdTotalExcludingTax = BigDecimal("203.18"),
        sgdTotalTax = BigDecimal("18.29"),
        lines = lines,
    )

    private fun handle(
        writer: FakeWriter,
        data: String,
    ) = CanonicalVersionCreatedHandler(writer).handle(envelope("CanonicalVersionCreated", data))

    @Test
    fun `INV-AC-24342 a canonical version is applied once with the exact facts and the repeat is ignored`() {
        val writer = FakeWriter()
        handle(writer, created()) shouldBe HandleOutcome.APPLIED
        writer.versions.single() shouldBe Triple(tenant.toString(), "E1", expected())
        handle(writer, created()) shouldBe HandleOutcome.IGNORED
        writer.versions.size shouldBe 2
    }

    @Test
    fun `INV-AC-24342 an absent direction is SALE and PURCHASE reaches the writer as PURCHASE`() {
        val writer = FakeWriter()
        handle(writer, created())
        handle(writer, created(routing = """{"direction":"PURCHASE","networkPath":"NOT_ON_NETWORK"}""", version = "2"))
        writer.versions[0].third.direction shouldBe Direction.SALE
        writer.versions[1].third.direction shouldBe Direction.PURCHASE
    }

    @Test
    fun `INV-AC-24342 a direction other than SALE or PURCHASE or a routingFacts without direction is a violation`() {
        listOf("""{"direction":"sale"}""", """{"direction":"BOTH"}""", """{"networkPath":"NOT_ON_NETWORK"}""").forEach {
            val writer = FakeWriter()
            shouldThrow<ContractViolation> { handle(writer, created(routing = it)) }
            writer.calls shouldBe 0
        }
    }

    @Test
    fun `INV-AC-24342 a payload without docsequence is a violation and the writer is not called`() {
        val writer = FakeWriter()
        shouldThrow<ContractViolation> { handle(writer, created(sequence = null)) }
        writer.calls shouldBe 0
    }

    @Test
    fun `INV-AC-24342 a line amount that is not a decimal string is a violation and the writer is not called`() {
        listOf(
            line(1, "abc", "9.00"),
            line(1, "1e3x", "9.00"),
            line(1, "100.00", ""),
        ).forEach {
            val writer = FakeWriter()
            shouldThrow<ContractViolation> { handle(writer, created(lines = listOf(it))) }
            writer.calls shouldBe 0
        }
        val numeric = """{"lineNumber":1,"lineAmount":100.0,"taxCode":"SR","taxAmount":"9.00"}"""
        val writer = FakeWriter()
        shouldThrow<ContractViolation> { handle(writer, created(lines = listOf(numeric))) }
        writer.calls shouldBe 0
    }

    @Test
    fun `INV-AC-24342 a version below 1, a blank period, a missing field or non-JSON is a violation`() {
        listOf(
            created(version = "0"),
            created(version = "1.5"),
            created(period = " "),
            created().replace("\"scopeVerdict\":\"SUBMITTABLE\",", ""),
            created().replace("\"transactionDate\":\"2026-02-10\"", "\"transactionDate\":\"10/02/2026\""),
            "not json",
            "[]",
        ).forEach {
            val writer = FakeWriter()
            shouldThrow<ContractViolation> { handle(writer, it) }
            writer.calls shouldBe 0
        }
    }

    @Test
    fun `INV-AC-24342 a line without taxCode reaches the writer with the empty tax code and is not dropped`() {
        val writer = FakeWriter()
        handle(writer, created(lines = listOf(line(1, "100.00", "9.00"), line(2, "50.50", "4.55", code = null))))
        writer.versions
            .single()
            .third.lines shouldBe
            listOf(
                DocumentLineFacts(1, "SR", BigDecimal("100.00"), BigDecimal("9.00")),
                DocumentLineFacts(2, "", BigDecimal("50.50"), BigDecimal("4.55")),
            )
    }

    @Test
    fun `INV-AC-24342 the optional exchange rate and SGD totals read as null when absent`() {
        val bare =
            """{"docUuid":"$doc","version":1,"docsequence":10,"transactionDate":"2026-02-10",""" +
                """"prescribedPeriodRef":"2026-Q1","scopeVerdict":"SUBMITTABLE",""" +
                """"notSubmittableReason":"BUYER_REJECTED",""" +
                """"canonicalDocument":{"currency":{"value":"SGD","classification":"NONE"},""" +
                """"lines":{"value":[],"classification":"NONE"}}}"""
        val writer = FakeWriter()
        handle(writer, bare) shouldBe HandleOutcome.APPLIED
        val facts = writer.versions.single().third
        facts.exchangeRate shouldBe null
        facts.sgdTotalExcludingTax shouldBe null
        facts.sgdTotalTax shouldBe null
        facts.notSubmittableReason shouldBe "BUYER_REJECTED"
        facts.lines shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24342 a period reassignment reaches reassignPeriod with its docsequence and new period`() {
        val writer = FakeWriter()
        val data =
            """{"docUuid":"$doc","docsequence":11,"oldPrescribedPeriodRef":"2026-Q1",""" +
                """"newPrescribedPeriodRef":"2026-Q2","cause":"DATE_CORRECTED"}"""
        val handler = PeriodReassignedHandler(writer)
        handler.handle(envelope("PeriodReassigned", data)) shouldBe HandleOutcome.APPLIED
        writer.reassigned.single() shouldBe listOf(tenant.toString(), "E1", doc, 11L, "2026-Q2")
        handler.handle(envelope("PeriodReassigned", data)) shouldBe HandleOutcome.IGNORED
        shouldThrow<ContractViolation> {
            handler.handle(envelope("PeriodReassigned", data.replace("\"docsequence\":11,", "")))
        }
        shouldThrow<ContractViolation> {
            handler.handle(envelope("PeriodReassigned", data.replace("2026-Q2", "")))
        }
        writer.reassigned.size shouldBe 2
    }

    private fun ack(id: String = "ACK-1") =
        """{"docUuid":"$doc","version":1,"acknowledgementId":"$id","timestamp":"2026-02-11T03:04:05Z"}"""

    @Test
    fun `INV-AC-24342 an acknowledgement is recorded once, a repeat is ignored and a blank id is a violation`() {
        val writer = FakeWriter()
        val handler = SubmissionAcknowledgedHandler(writer)
        handler.handle(envelope("SubmissionAcknowledged", ack())) shouldBe HandleOutcome.APPLIED
        writer.acknowledged.single() shouldBe
            listOf(tenant.toString(), doc, 1, "ACK-1", Instant.parse("2026-02-11T03:04:05Z"))
        handler.handle(envelope("SubmissionAcknowledged", ack())) shouldBe HandleOutcome.IGNORED
        val before = writer.calls
        listOf(
            ack(""),
            ack(" "),
            ack().replace("2026-02-11T03:04:05Z", "yesterday"),
            ack().replace("\"version\":1", "\"version\":0"),
        ).forEach { shouldThrow<ContractViolation> { handler.handle(envelope("SubmissionAcknowledged", it)) } }
        writer.calls shouldBe before
    }

    @Test
    fun `INV-AC-24342 an envelope without an entity attribute is refused before any handler runs`() {
        val json = EnvelopeJson()
        val text = json.write(envelope("CanonicalVersionCreated", created()))
        val without = text.replace(Regex(""","entityref":"[^"]*""""), "")
        (without == text) shouldBe false
        shouldThrow<ContractViolation> { json.read(without) }
    }
}
