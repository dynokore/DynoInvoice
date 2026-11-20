package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonCanonicalVersionCreatedDataReader
import com.dynokore.gst.submission.application.register.CanonicalVersionCreatedData
import com.dynokore.gst.submission.domain.register.ListFactsPolicy
import com.dynokore.gst.submission.domain.register.ListFactsResult
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.LocalDate

/**
 * The reader over a sample of platform-contracts canonical/examples/valid/, held as the constant SAMPLE below
 * (canonical/examples/valid/canonical-version-created-with-party-details.json of platform-contracts commit
 * 9d2562813d16daca6be35d1ffb916c1209967c28 (9d25628), copied verbatim; no test-resource path in scope).
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

    private val d0 = "0a1b2c3d-0000-4000-8000-00000000d0d0"

    private fun factsOf(d: CanonicalVersionCreatedData) =
        listOf(d.scopeVerdict, d.notSubmittableReason, d.precedingDocUuid)

    private fun with(vararg members: Pair<String, Any?>): String =
        (mapper.readTree(sample) as ObjectNode)
            .also { n ->
                members.forEach { (k, v) ->
                    when (v) {
                        is String -> n.put(k, v)
                        is Int -> n.put(k, v)
                        else -> n.putNull(k)
                    }
                }
            }.toString()

    @Test
    fun `INV-AC-24492 valid events E1 to E4 keep verdict, reason and preceding UUID`() {
        val e1 = reader.read(sample)
        factsOf(e1) shouldBe listOf("SUBMITTABLE", null, null)
        val e2 =
            reader.read(
                with(
                    "scopeVerdict" to "NOT_SUBMITTABLE",
                    "notSubmittableReason" to "BUYER_REJECTED",
                    "precedingDocUuid" to d0,
                ),
            )
        factsOf(e2) shouldBe
            listOf("NOT_SUBMITTABLE", "BUYER_REJECTED", d0)
        val e3 = reader.read(with("scopeVerdict" to "NOT_SUBMITTABLE"))
        factsOf(e3) shouldBe listOf("NOT_SUBMITTABLE", null, null)
        val e4 = reader.read(with("scopeVerdict" to "PRE_OBLIGATION"))
        factsOf(e4) shouldBe listOf("PRE_OBLIGATION", null, null)
        listOf("ISSUANCE_REFUSED", "NETTED_INTO_REVISION").forEach {
            val d = reader.read(with("scopeVerdict" to "NOT_SUBMITTABLE", "notSubmittableReason" to it))
            d.notSubmittableReason shouldBe it
        }
    }

    private fun violation(
        json: String,
        member: String,
        value: String,
    ) {
        val m = shouldThrow<ContractViolation> { reader.read(json) }.message!!
        m shouldContain member
        m shouldNotContain value
    }

    @Test
    fun `INV-AC-24492 E5 a reason with SUBMITTABLE is a ContractViolation naming the member`() {
        violation(with("notSubmittableReason" to "BUYER_REJECTED"), "notSubmittableReason", "BUYER_REJECTED")
    }

    @Test
    fun `INV-AC-24492 E6 and E7 a lower-case or unknown reason is a ContractViolation naming the member`() {
        val nsv = "scopeVerdict" to "NOT_SUBMITTABLE"
        violation(with(nsv, "notSubmittableReason" to "buyer_rejected"), "notSubmittableReason", "buyer_rejected")
        violation(with(nsv, "notSubmittableReason" to "UNKNOWN_CAUSE"), "notSubmittableReason", "UNKNOWN_CAUSE")
        violation(with(nsv, "notSubmittableReason" to " BUYER_REJECTED"), "notSubmittableReason", "BUYER_REJECTED ")
        violation(with(nsv, "notSubmittableReason" to 7), "notSubmittableReason", "7")
        violation(with(nsv, "notSubmittableReason" to null), "notSubmittableReason", "null")
    }

    @Test
    fun `INV-AC-24492 E8 E9 E10 a malformed preceding UUID is a ContractViolation naming the member`() {
        violation(with("precedingDocUuid" to "not-a-uuid"), "precedingDocUuid", "not-a-uuid")
        violation(with("precedingDocUuid" to 7), "precedingDocUuid", "7")
        violation(with("precedingDocUuid" to d0.uppercase()), "precedingDocUuid", d0.uppercase())
        violation(with("precedingDocUuid" to null), "precedingDocUuid", "null")
        violation(with("precedingDocUuid" to "1-1-1-1-1"), "precedingDocUuid", "1-1-1-1-1")
    }

    @Test
    fun `INV-AC-16339 a version below one a bad date and non-JSON text are a ContractViolation`() {
        shouldThrow<ContractViolation> { reader.read(sample.replace("\"version\":1", "\"version\":0")) }
        shouldThrow<ContractViolation> { reader.read(sample.replace("2026-03-20\",\"prescribed", "x\",\"prescribed")) }
        shouldThrow<ContractViolation> { reader.read("not json {") }
        shouldThrow<ContractViolation> { reader.read("[]") }
    }

    private fun withNet(net: String): String =
        (mapper.readTree(sample) as ObjectNode)
            .also { it.set("netDerivation", mapper.readTree(net)) }
            .toString()

    private val cnA = "7c9e6679-7425-40de-944b-e07fc1f90ae7"

    @Test
    fun `INV-AC-25344 netDerivation is read with the base version and the credit notes`() {
        val json = """{"kind":"SYSTEM_DERIVED","baseVersion":2,"creditNotes":[{"docUuid":"$cnA","version":3}]}"""
        val net = reader.read(withNet(json)).netDerivation
        net?.baseVersion shouldBe 2
        net?.creditNotes?.map { it.docUuid to it.version } shouldBe listOf(cnA to 3)
    }

    @Test
    fun `INV-AC-25344 an absent netDerivation member reads as null`() {
        reader.read(sample).netDerivation shouldBe null
    }

    @Test
    fun `INV-AC-25344 a malformed netDerivation is a ContractViolation naming the member`() {
        val bad =
            listOf(
                "[]",
                """{"baseVersion":2,"creditNotes":[{"docUuid":"$cnA","version":3}]}""",
                """{"kind":"SYSTEM_DERIVED","baseVersion":0,"creditNotes":[{"docUuid":"$cnA","version":3}]}""",
                """{"kind":"SYSTEM_DERIVED","baseVersion":2,"creditNotes":[]}""",
                """{"kind":"SYSTEM_DERIVED","baseVersion":2,"creditNotes":[{"docUuid":"X","version":3}]}""",
                """{"kind":"SYSTEM_DERIVED","baseVersion":2,"creditNotes":[{"docUuid":"$cnA","version":0}]}""",
                """{"kind":"SYSTEM_DERIVED","baseVersion":2,"creditNotes":""" +
                    """[{"docUuid":"$cnA","version":3},{"docUuid":"$cnA","version":3}]}""",
            )
        bad.forEach {
            shouldThrow<ContractViolation> { reader.read(withNet(it)) }.message shouldContain "netDerivation"
        }
    }
}

/** canonical-version-created-with-party-details.json of platform-contracts commit 9d25628, verbatim. */
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
