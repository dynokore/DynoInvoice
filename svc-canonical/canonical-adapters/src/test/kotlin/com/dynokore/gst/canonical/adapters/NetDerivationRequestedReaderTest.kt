package com.dynokore.gst.canonical.adapters

import com.dynokore.gst.canonical.adapters.events.JacksonNetDerivedVersions
import com.dynokore.gst.canonical.adapters.events.NetDerivationRequestedReader
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.document.ScopeVerdict
import com.dynokore.gst.canonical.domain.document.SpecificationVersion
import com.dynokore.gst.canonical.domain.netting.Derived
import com.dynokore.gst.canonical.domain.netting.DocumentFunction
import com.dynokore.gst.canonical.domain.netting.NetBase
import com.dynokore.gst.canonical.domain.netting.NetCreditNote
import com.dynokore.gst.canonical.domain.netting.NetDerivation
import com.dynokore.gst.canonical.domain.netting.NetLine
import com.dynokore.gst.canonical.domain.netting.NetSubtotal
import com.dynokore.gst.canonical.domain.netting.NetTotals
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class NetDerivationRequestedReaderTest {
    private val reader = NetDerivationRequestedReader()
    private val schema = NetContractSchemas.submissionSchemaText("net-derivation-requested-payload.schema.json")
    private val original = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
    private val noteA = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    private val noteB = "9b2e6c58-8f45-4d6c-9d0a-2a7d5f0e6b11"

    private fun payload(
        original: String? = "\"${this.original}\"",
        base: String? = "1",
        notes: String? = "[{\"docUuid\":\"$noteA\",\"version\":1}]",
    ) = buildList {
        original?.let { add("\"originalDocUuid\":$it") }
        base?.let { add("\"expectedBaseVersion\":$it") }
        notes?.let { add("\"creditNotes\":$it") }
    }.joinToString(",", "{", "}")

    private fun accepts(data: String): Boolean =
        try {
            reader.read(data)
            true
        } catch (_: ContractViolation) {
            false
        }

    private fun schemaAccepts(data: String): Boolean = NetContractSchemas.errors(schema, data).isEmpty()

    /** The reader and the pinned schema give the same answer; a case that the schema rejects must be refused. */
    private fun agree(
        data: String,
        valid: Boolean,
    ) {
        schemaAccepts(data) shouldBe valid
        accepts(data) shouldBe valid
    }

    private fun notes(count: Int) =
        (1..count).joinToString(",", "[", "]") {
            "{\"docUuid\":\"00000000-0000-4000-8000-%012d\",\"version\":1}".format(it)
        }

    @Test
    fun `INV-AC-25342 a valid request is read into the original, the base version and the credit note list`() {
        val two = "[{\"docUuid\":\"$noteA\",\"version\":2},{\"docUuid\":\"$noteB\",\"version\":1}]"
        val request = reader.read(payload(notes = two))
        request.originalDocUuid shouldBe UUID.fromString(original)
        request.expectedBaseVersion shouldBe 1
        request.creditNotes.map { it.docUuid to it.version } shouldBe
            listOf(UUID.fromString(noteA) to 2, UUID.fromString(noteB) to 1)
        agree(payload(), true)
    }

    @Test
    fun `INV-AC-25342 valid payloads the schema accepts are read, including extra members and an integral 1_0`() {
        agree(payload().dropLast(1) + ",\"extra\":{\"x\":1}}", true)
        agree(payload(base = "1.0"), true)
        agree(payload(base = "2"), true)
        agree(payload(notes = notes(50)), true)
        agree(payload(original = "\"${original.uppercase()}\""), true)
    }

    @Test
    fun `INV-AC-25342 payloads the schema rejects are refused by the reader as the schema refuses them`() {
        agree("[]", false)
        agree(payload(original = null), false)
        agree(payload(base = null), false)
        agree(payload(notes = null), false)
        agree(payload(original = "123"), false)
        agree(payload(original = "null"), false)
        agree(payload(original = "\"not-a-uuid\""), false)
        agree(payload(base = "0"), false)
        agree(payload(base = "\"1\""), false)
        agree(payload(base = "1.5"), false)
        agree(payload(base = "-1"), false)
        agree(payload(notes = "[]"), false)
        agree(payload(notes = notes(51)), false)
        agree(payload(notes = "{}"), false)
        agree(payload(notes = "[1]"), false)
        agree(payload(notes = "[{\"docUuid\":\"$noteA\"}]"), false)
        agree(payload(notes = "[{\"version\":1}]"), false)
        agree(payload(notes = "[{\"docUuid\":\"$noteA\",\"version\":0}]"), false)
        agree(payload(notes = "[{\"docUuid\":\"$noteA\",\"version\":1},{\"docUuid\":\"$noteA\",\"version\":1}]"), false)
    }

    @Test
    fun `INV-AC-25342 look-alike members and values are refused, never matched loosely`() {
        val note = "[{\"docUuid\":\"$noteA\",\"version\":1}]"
        agree("{\"originalDocUuid\":\"$original\",\"expectedBaseversion\":1,\"creditNotes\":$note}", false)
        agree("{\"originalDocUuid\":\"$original\",\"expectedBaseVersion\":1,\"creditnotes\":$note}", false)
        agree("{\"originaldocuuid\":\"$original\",\"expectedBaseVersion\":1,\"creditNotes\":$note}", false)
        agree(payload(original = "\"$original \""), false)
        agree(payload(original = "\"{$original}\""), false)
        agree(payload(original = "\"urn:uuid:$original\""), false)
        agree(payload(original = "\"${original.replace("-", "")}\""), false)
        agree(payload(notes = "[{\"docuuid\":\"$noteA\",\"version\":1}]"), false)
        accepts("{").shouldBe(false)
        accepts("").shouldBe(false)
    }

    @Test
    fun `INV-AC-25342 a credit note that repeats a docUuid with another version is refused beyond the schema`() {
        val data = payload(notes = "[{\"docUuid\":\"$noteA\",\"version\":1},{\"docUuid\":\"$noteA\",\"version\":2}]")
        schemaAccepts(data) shouldBe true
        accepts(data) shouldBe false
    }

    // ---- the version builder ----

    private val invoiceCanonical =
        """{"documentTypeCode":{"value":"380","classification":"NONE"},""" +
            """"currency":{"value":"SGD","classification":"NONE"},""" +
            """"documentNumber":{"value":"INV-1","classification":"NONE"},""" +
            """"parties":{"value":{"supplier":{"identifierScheme":"UEN","identifierValue":"201912345A"},""" +
            """"buyer":{"identifierScheme":"UEN","identifierValue":"201912345B"}},"classification":"IDENTIFYING"},""" +
            """"lines":{"value":[{"lineNumber":1,"description":"Consulting","quantity":1,"unitPrice":"9000.00",""" +
            """"lineAmount":"9000.00","taxCode":"SR","taxAmount":"810.00","unitCode":"EA"}],""" +
            """"classification":"NONE"},""" +
            """"taxSubtotals":{"value":[{"taxCode":"SR","taxableAmount":"9000.00","taxAmount":"810.00"}],""" +
            """"classification":"NONE"},""" +
            """"totals":{"value":{"totalExcludingTax":"9000.00","totalTax":"810.00","totalIncludingTax":"9810.00"},""" +
            """"classification":"NONE"},"invoiceNote":{"value":"keep me","classification":"NONE"}}"""

    private val line =
        NetLine(1, BigDecimal.ONE, BigDecimal("9000.00"), BigDecimal("9000.00"), "SR", BigDecimal("810.00"))
    private val entity = UUID.randomUUID()
    private val base =
        NetBase(
            entity,
            Direction.PURCHASE,
            DocumentFunction.INVOICE,
            "SGD",
            null,
            listOf(line),
            listOf(NetSubtotal("SR", BigDecimal("9000.00"), BigDecimal("810.00"))),
            NetTotals(BigDecimal("9000.00"), BigDecimal("810.00"), BigDecimal("9810.00")),
        )
    private val note =
        NetCreditNote(
            UUID.fromString(noteA),
            1,
            UUID.fromString(original),
            entity,
            Direction.PURCHASE,
            DocumentFunction.CREDIT_NOTE,
            "SGD",
            null,
            listOf(NetLine(1, BigDecimal.ONE, BigDecimal("1000.00"), BigDecimal("1000.00"), "SR", BigDecimal("90.00"))),
            listOf(NetSubtotal("SR", BigDecimal("1000.00"), BigDecimal("90.00"))),
            NetTotals(BigDecimal("1000.00"), BigDecimal("90.00"), BigDecimal("1090.00")),
        )

    private fun build(canonical: String = invoiceCanonical): Pair<NewVersion, Derived> {
        val derived = NetDerivation.derive(UUID.fromString(original), base, listOf(note)) as Derived
        val stored =
            NewVersion(
                canonical,
                ScopeVerdict.IN_SCOPE,
                "scope-1",
                "map-1",
                null,
                SpecificationVersion.PINT_SG,
                LocalDate.of(2026, 3, 20),
                "svc:old",
            )
        val built = JacksonNetDerivedVersions { _, _ -> stored }.build(UUID.fromString(original), 1, derived)
        return built to derived
    }

    private val tree = JsonMapper.builder().build()
    private val replaced = setOf("lines", "taxSubtotals", "totals")

    @Test
    fun `INV-AC-25342 the version builder replaces exactly lines, taxSubtotals and totals, nothing else`() {
        val (built, _) = build()
        val before = tree.readTree(invoiceCanonical) as ObjectNode
        val after = tree.readTree(built.canonical) as ObjectNode
        after.propertyNames().asSequence().toList() shouldBe before.propertyNames().asSequence().toList()
        before.propertyNames().asSequence().filter { it !in replaced }.forEach { name ->
            after.get(name).toString() shouldBe before.get(name).toString()
        }
        replaced.forEach {
            after.get(it).get("classification").toString() shouldBe before.get(it).get("classification").toString()
        }
        (after.get("lines").get("value").toString() != before.get("lines").get("value").toString()) shouldBe true
        // the metadata of the base version is carried, not rebuilt
        built.scopeVerdict shouldBe ScopeVerdict.IN_SCOPE
        built.specificationVersion shouldBe SpecificationVersion.PINT_SG
        built.transactionDate shouldBe LocalDate.of(2026, 3, 20)
    }

    @Test
    fun `INV-AC-25342 the derived values are decimal strings of scale 2 and the base line is kept as it was`() {
        val (built, _) = build()
        val after = tree.readTree(built.canonical)
        val lines = after.get("lines").get("value")
        lines.size() shouldBe 2
        lines.get(0).toString() shouldBe
            tree
                .readTree(invoiceCanonical)
                .get("lines")
                .get("value")
                .get(0)
                .toString()
        lines.get(1).get("lineAmount").asString() shouldBe "-1000.00"
        lines.get(1).get("taxAmount").asString() shouldBe "-90.00"
        lines.get(1).get("unitPrice").asString() shouldBe "-1000.00"
        lines.get(1).get("taxCode").asString() shouldBe "SR"
        after
            .get("taxSubtotals")
            .get("value")
            .get(0)
            .get("taxableAmount")
            .asString() shouldBe "8000.00"
        val totals = after.get("totals").get("value")
        totals.get("totalExcludingTax").asString() shouldBe "8000.00"
        totals.get("totalTax").asString() shouldBe "720.00"
        totals.get("totalIncludingTax").asString() shouldBe "8720.00"
    }

    @Test
    fun `INV-AC-25342 the built canonical document validates against the pinned canonical document schema`() {
        val (built, _) = build()
        NetContractSchemas.errors(NetContractSchemas.canonicalDocumentSchema(), invoiceCanonical).shouldBeEmpty()
        NetContractSchemas.errors(NetContractSchemas.canonicalDocumentSchema(), built.canonical).shouldBeEmpty()
        // the schema is strict: a built document that lost its currency fails
        val without = (tree.readTree(built.canonical) as ObjectNode).also { it.remove("currency") }
        NetContractSchemas.errors(NetContractSchemas.canonicalDocumentSchema(), without.toString()).shouldNotBeEmpty()
    }
}
