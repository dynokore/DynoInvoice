package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonCanonicalVersionCreatedDataReader
import com.dynokore.gst.submission.domain.register.ListFactsPolicy
import com.dynokore.gst.submission.domain.register.ListFactsResult
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.LocalDate

/**
 * The reader over a sample of platform-contracts canonical/examples/valid/, held as the constant SAMPLE below
 * (canonical-version-created-with-party-details.json, copied unchanged; no test-resource path in scope).
 * INV-AC-16339.
 */
class JacksonCanonicalVersionCreatedDataReaderTest {
    private val reader = JacksonCanonicalVersionCreatedDataReader()
    private val mapper = ObjectMapper()
    private val sample = SAMPLE

    private fun without(member: String): String =
        (mapper.readTree(sample) as ObjectNode)
            .also {
                it.remove(member)
            }.toString()

    @Test
    fun `INV-AC-16339 the sample is read with the expected members and lacks only routing facts`() {
        val d = reader.read(sample)
        d.docUuid shouldBe "3fa85f64-5717-4562-b3fc-2c963f66afa6"
        d.version shouldBe 1
        d.transactionDate shouldBe LocalDate.of(2026, 3, 20)
        d.prescribedPeriodRef shouldBe "2026-Q1"
        d.scopeVerdict shouldBe "SUBMITTABLE"
        d.payload.keys shouldBe setOf("canonicalDocument")
        ListFactsPolicy.extract(d.payload).shouldBeInstanceOf<ListFactsResult.Incomplete>().missing shouldBe
            setOf("direction", "counterpartyName", "counterpartyUen")
    }

    @Test
    fun `INV-AC-16339 routingFacts are kept and the sample with routing facts extracts complete facts`() {
        val withRouting =
            (mapper.readTree(sample) as ObjectNode)
                .also { it.putObject("routingFacts").put("direction", "SALE") }
                .toString()
        val d = reader.read(withRouting)
        d.payload.keys shouldBe setOf("canonicalDocument", "routingFacts")
        ListFactsPolicy.extract(d.payload).shouldBeInstanceOf<ListFactsResult.Complete>()
    }

    @Test
    fun `INV-AC-16339 each missing member is a ContractViolation`() {
        listOf("docUuid", "version", "transactionDate", "prescribedPeriodRef", "scopeVerdict").forEach {
            shouldThrow<ContractViolation> { reader.read(without(it)) }
        }
    }

    @Test
    fun `INV-AC-16339 a version below one a bad date and non-JSON text are a ContractViolation`() {
        shouldThrow<ContractViolation> { reader.read(sample.replace("\"version\":1", "\"version\":0")) }
        shouldThrow<ContractViolation> { reader.read(sample.replace("2026-03-20\",\"prescribed", "x\",\"prescribed")) }
        shouldThrow<ContractViolation> { reader.read("not json {") }
        shouldThrow<ContractViolation> { reader.read("[]") }
    }
}

/** canonical-version-created-with-party-details.json of platform-contracts, byte for byte. */
private const val SAMPLE =
    "{\"docUuid\":\"3fa85f64-5717-4562-b3fc-2c963f66afa6\",\"version\":1,\"docsequence\":1,\"transaction" +
        "Date\":\"2026-03-20\",\"prescribedPeriodRef\":\"2026-Q1\",\"scopeVerdict\":\"SUBMITTABLE\",\"appliedPa" +
        "ckVersions\":[{\"packIdentifier\":\"sg-gst\",\"version\":\"1.0.0\"}],\"traceReference\":\"trace-0001\"," +
        "\"canonicalDocument\":{\"documentTypeCode\":{\"value\":\"380\",\"classification\":\"NONE\"},\"currency\"" +
        ":{\"value\":\"SGD\",\"classification\":\"NONE\"},\"parties\":{\"value\":{\"supplier\":{\"identifierScheme" +
        "\":\"SG_UEN\",\"identifierValue\":\"201912345A\",\"name\":\"Acme Pte Ltd\",\"gstn\":\"M2-1234567-8\",\"add" +
        "ress\":{\"line1\":\"1 Raffles Place\",\"postalCode\":\"048616\",\"countryCode\":\"SG\"}},\"buyer\":{\"iden" +
        "tifierScheme\":\"SG_UEN\",\"identifierValue\":\"201888888A\",\"name\":\"Acme Pte Ltd\",\"gstn\":\"M2-123" +
        "4567-8\",\"address\":{\"line1\":\"1 Raffles Place\",\"postalCode\":\"048616\",\"countryCode\":\"SG\"}}},\"" +
        "classification\":\"IDENTIFYING\"},\"lines\":{\"value\":[{\"lineNumber\":1,\"description\":\"Consulting" +
        "\",\"quantity\":1,\"unitPrice\":\"100.00\",\"lineAmount\":\"100.00\",\"taxCode\":\"SR\",\"taxAmount\":\"9.00" +
        "\",\"unitCode\":\"EA\"}],\"classification\":\"NONE\"},\"taxSubtotals\":{\"value\":[{\"taxCode\":\"SR\",\"tax" +
        "ableAmount\":\"100.00\",\"taxAmount\":\"9.00\"}],\"classification\":\"NONE\"},\"totals\":{\"value\":{\"tot" +
        "alExcludingTax\":\"100.00\",\"totalTax\":\"9.00\",\"totalIncludingTax\":\"109.00\"},\"classification\":" +
        "\"NONE\"},\"documentNumber\":{\"value\":\"INV-2026-000123\",\"classification\":\"NONE\"},\"documentDate" +
        "\":{\"value\":\"2026-03-20\",\"classification\":\"NONE\"},\"invoiceNote\":{\"value\":\"Thanks\",\"classifi" +
        "cation\":\"NONE\"}}}"
