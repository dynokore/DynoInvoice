package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.serviceruntime.contract.ContractValidator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.ObjectMapper

/**
 * ING-006, ING-002 and ING-022 (coordinator rulings INV-Q-11260 A and INV-Q-11264 A) against the pinned catalogue snapshot (`src/implTest/resources/contracts/catalogue/errors/error-codes.yaml`):
 * the snapshot holds exactly one ING-006 row, the enum has exactly that one entry, and its code, title (the row's
 * condition in full) and owner equal the row's (coordinator rulings INV-Q-8795 A and INV-Q-8797 A). The status is the
 * 422 that INV-T-8799 declares for ING-006 on `intakeAggregationBatch`. The rendered envelope validates against the
 * pinned schema.
 */
class IngestionErrorCodeTest {
    private class Row(
        val code: String,
        val condition: String,
        val severity: String,
        val disposition: String,
        val owner: String,
    )

    private val mapper = ObjectMapper()
    private val contracts = ContractValidator()

    private val allRows: List<Row> =
        run {
            val text = checkNotNull(javaClass.getResource("/contracts/catalogue/errors/error-codes.yaml")).readText()
            Yaml().load<Map<String, List<Map<String, Any?>>>>(text).getValue("error_codes").map {
                Row(
                    it["code"].toString(),
                    it["condition"].toString(),
                    it["severity"].toString(),
                    it["disposition"].toString(),
                    it["owner"].toString(),
                )
            }
        }

    private val rows: List<Row> = allRows.filter { it.code == "ING-006" }

    private fun rowsOf(code: String): List<Row> = allRows.filter { it.code == code }

    @Test
    fun `the snapshot holds exactly one ING-006 row and the enum exactly one entry`() {
        rows.size shouldBe 1
        IngestionErrorCode.entries.size shouldBe 3
        IngestionErrorCode.Owner.entries.size shouldBe 1
    }

    @Test
    fun `the code, title, owner and status of the entry are the row's and the declared 422`() {
        val row = rows.single()
        val entry = IngestionErrorCode.AGGREGATE_TYPE_NOT_PERMITTED
        entry shouldBe IngestionErrorCode.AGGREGATE_TYPE_NOT_PERMITTED
        entry.code shouldBe row.code
        entry.title shouldBe row.condition
        entry.owner.text shouldBe row.owner
        entry.httpStatus shouldBe 422
    }

    @Test
    fun `renderIngestion gives a 422 envelope with the row's severity and disposition that validates`() {
        val row = rows.single()
        val code = IngestionErrorCode.AGGREGATE_TYPE_NOT_PERMITTED
        val response = Problems.renderIngestion(code, "corr-1", "what went wrong")
        response.status shouldBe 422
        response.headers shouldBe mapOf("Content-Type" to "application/problem+json")
        val body = mapper.readTree(response.body)
        contracts.schemaErrors("service-runtime/schemas/error-envelope.schema.json", body).shouldBeEmpty()
        body.get("code").asString() shouldBe row.code
        body.get("title").asString() shouldBe row.condition
        body.get("status").asInt() shouldBe 422
        body.get("severity").asString() shouldBe row.severity
        body.get("disposition").asString() shouldBe row.disposition
        body.get("remediationOwner").asString() shouldBe row.owner
        body.get("correlationId").asString() shouldBe "corr-1"
        body.get("detail").asString() shouldBe "what went wrong"
        mapper.readTree(Problems.renderIngestion(code, "c").body).has("detail") shouldBe false
    }

    @Test
    fun `the entries are ING-006, ING-002 and ING-022 in this order`() {
        IngestionErrorCode.entries.map { it.code } shouldBe listOf("ING-006", "ING-002", "ING-022")
    }

    @Test
    fun `ING-002 is the row's code, title and owner with the declared 409`() =
        assertEntry(IngestionErrorCode.SOURCE_REVISION_LOWER, "ING-002", 409)

    @Test
    fun `ING-022 is the row's code, title and owner with the declared 422`() =
        assertEntry(IngestionErrorCode.AGGREGATE_NUMBER_NOT_BUILDABLE, "ING-022", 422)

    @Test
    fun `renderIngestion gives a 409 envelope for ING-002 that validates`() =
        assertRendered(IngestionErrorCode.SOURCE_REVISION_LOWER, "ING-002", 409)

    @Test
    fun `renderIngestion gives a 422 envelope for ING-022 that validates`() =
        assertRendered(IngestionErrorCode.AGGREGATE_NUMBER_NOT_BUILDABLE, "ING-022", 422)

    private fun assertEntry(
        entry: IngestionErrorCode,
        code: String,
        status: Int,
    ) {
        val row = rowsOf(code).single()
        entry.code shouldBe row.code
        entry.title shouldBe row.condition
        entry.owner.text shouldBe row.owner
        entry.httpStatus shouldBe status
    }

    private fun assertRendered(
        entry: IngestionErrorCode,
        code: String,
        status: Int,
    ) {
        val row = rowsOf(code).single()
        val response = Problems.renderIngestion(entry, "corr-1", "what went wrong")
        response.status shouldBe status
        val body = mapper.readTree(response.body)
        contracts.schemaErrors("service-runtime/schemas/error-envelope.schema.json", body).shouldBeEmpty()
        body.get("code").asString() shouldBe row.code
        body.get("title").asString() shouldBe row.condition
        body.get("status").asInt() shouldBe status
        body.get("severity").asString() shouldBe row.severity
        body.get("disposition").asString() shouldBe row.disposition
    }
}
