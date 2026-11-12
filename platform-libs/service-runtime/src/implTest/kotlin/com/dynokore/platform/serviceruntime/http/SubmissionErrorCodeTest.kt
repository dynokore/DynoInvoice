package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.serviceruntime.contract.ContractValidator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.ObjectMapper

/**
 * SUB-008 and SUB-025 (coordinator ruling at the reviewer stop of INV-T-20607; INV-T-21366) against the pinned
 * catalogue snapshot (`src/implTest/resources/contracts/catalogue/errors/error-codes.yaml`): the snapshot holds
 * exactly one row for each of the two codes, the enum has exactly two entries, and their fields equal the rows'.
 * Rows of other codes are not compared.
 */
class SubmissionErrorCodeTest {
    private class Row(
        val code: String,
        val condition: String,
        val severity: String,
        val disposition: String,
        val owner: String,
    )

    private val codes = setOf("SUB-008", "SUB-025")
    private val mapper = ObjectMapper()
    private val contracts = ContractValidator()

    private val rows: List<Row> =
        run {
            val text = checkNotNull(javaClass.getResource("/contracts/catalogue/errors/error-codes.yaml")).readText()
            Yaml()
                .load<Map<String, List<Map<String, Any?>>>>(text)
                .getValue("error_codes")
                .filter { it["code"].toString() in codes }
                .map {
                    Row(
                        it["code"].toString(),
                        it["condition"].toString(),
                        it["severity"].toString(),
                        it["disposition"].toString(),
                        it["owner"].toString(),
                    )
                }
        }

    @Test
    fun `the snapshot holds exactly one row for each of SUB-008 and SUB-025 and the enum exactly two entries`() {
        rows.map { it.code }.sorted() shouldBe listOf("SUB-008", "SUB-025")
        SubmissionErrorCode.entries.size shouldBe 2
        SubmissionErrorCode.Owner.entries.size shouldBe 1
        SubmissionErrorCode.entries.map { it.code } shouldBe listOf("SUB-008", "SUB-025")
        SubmissionErrorCode.entries shouldBe
            listOf(SubmissionErrorCode.IN_FLIGHT_BREACH, SubmissionErrorCode.VERSION_NOT_REPLAYABLE)
    }

    @Test
    @Suppress("ktlint:standard:max-line-length")
    fun `INV-AC-21365 SUB-008 and SUB-025 are the rows' code, title, severity, disposition and owner with the declared 409`() {
        SubmissionErrorCode.entries.forEachIndexed { i, entry ->
            val row = rows.single { it.code == entry.code }
            entry.code shouldBe row.code
            entry.title shouldBe row.condition
            entry.severity shouldBe row.severity
            entry.disposition shouldBe row.disposition
            entry.owner.text shouldBe row.owner
            entry.httpStatus shouldBe 409
            entry.httpStatus shouldBe HttpStatusCodes.CONFLICT
            entry.code shouldBe listOf("SUB-008", "SUB-025")[i]
        }
    }

    @Test
    fun `INV-AC-21365 renderSubmission gives a 409 envelope for each code that validates`() {
        SubmissionErrorCode.entries.forEach { entry ->
            val row = rows.single { it.code == entry.code }
            val response = Problems.renderSubmission(entry, "corr-1", "what went wrong")
            response.status shouldBe 409
            response.headers shouldBe mapOf("Content-Type" to "application/problem+json")
            response.headers.containsKey("Retry-After") shouldBe false
            val body = mapper.readTree(response.body)
            contracts.schemaErrors("service-runtime/schemas/error-envelope.schema.json", body).shouldBeEmpty()
            body.get("type").asString() shouldBe Problems.typeOf(entry.code)
            body.get("code").asString() shouldBe row.code
            body.get("title").asString() shouldBe row.condition
            body.get("status").asInt() shouldBe 409
            body.get("severity").asString() shouldBe row.severity
            body.get("disposition").asString() shouldBe row.disposition
            body.get("remediationOwner").asString() shouldBe row.owner
            body.get("correlationId").asString() shouldBe "corr-1"
            body.get("detail").asString() shouldBe "what went wrong"
            mapper.readTree(Problems.renderSubmission(entry, "c").body).has("detail") shouldBe false
        }
    }
}
