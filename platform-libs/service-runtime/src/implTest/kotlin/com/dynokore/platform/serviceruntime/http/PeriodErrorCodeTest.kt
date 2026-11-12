package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.serviceruntime.contract.ContractValidator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.ObjectMapper

/**
 * PER-001 (coordinator ruling of round z, 2026-11-12; INV-T-20923) against the pinned catalogue snapshot
 * (`src/implTest/resources/contracts/catalogue/errors/error-codes.yaml`): the snapshot holds exactly one PER-001
 * row, the enum has exactly that one entry, and its fields equal the row's. Rows of other codes are not compared.
 */
class PeriodErrorCodeTest {
    private class Row(
        val code: String,
        val condition: String,
        val severity: String,
        val disposition: String,
        val owner: String,
    )

    private val mapper = ObjectMapper()
    private val contracts = ContractValidator()

    private val rows: List<Row> =
        run {
            val text = checkNotNull(javaClass.getResource("/contracts/catalogue/errors/error-codes.yaml")).readText()
            Yaml()
                .load<Map<String, List<Map<String, Any?>>>>(text)
                .getValue("error_codes")
                .filter { it["code"].toString() == "PER-001" }
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
    fun `the snapshot holds exactly one PER-001 row and the enum exactly one entry`() {
        rows.size shouldBe 1
        PeriodErrorCode.entries.size shouldBe 1
        PeriodErrorCode.Owner.entries.size shouldBe 1
        PeriodErrorCode.entries.map { it.code } shouldBe listOf("PER-001")
    }

    @Test
    fun `INV-AC-20922 PER-001 is the row's code, title, severity, disposition and owner with the declared 422`() {
        val row = rows.single()
        val entry = PeriodErrorCode.entries.single()
        entry shouldBe PeriodErrorCode.PERIOD_NOT_READY
        entry.code shouldBe row.code
        entry.title shouldBe row.condition
        entry.severity shouldBe row.severity
        entry.disposition shouldBe row.disposition
        entry.owner.text shouldBe row.owner
        entry.httpStatus shouldBe 422
        entry.httpStatus shouldBe HttpStatusCodes.UNPROCESSABLE
    }

    @Test
    fun `INV-AC-20922 renderPeriod gives a 422 envelope for PER-001 that validates`() {
        val row = rows.single()
        val response = Problems.renderPeriod(PeriodErrorCode.PERIOD_NOT_READY, "corr-1", "what went wrong")
        response.status shouldBe 422
        response.headers shouldBe mapOf("Content-Type" to "application/problem+json")
        response.headers.containsKey("Retry-After") shouldBe false
        val body = mapper.readTree(response.body)
        contracts.schemaErrors("service-runtime/schemas/error-envelope.schema.json", body).shouldBeEmpty()
        body.get("type").asString() shouldBe Problems.typeOf("PER-001")
        body.get("code").asString() shouldBe row.code
        body.get("title").asString() shouldBe row.condition
        body.get("status").asInt() shouldBe 422
        body.get("severity").asString() shouldBe row.severity
        body.get("disposition").asString() shouldBe row.disposition
        body.get("remediationOwner").asString() shouldBe row.owner
        body.get("correlationId").asString() shouldBe "corr-1"
        body.get("detail").asString() shouldBe "what went wrong"
        mapper.readTree(Problems.renderPeriod(PeriodErrorCode.PERIOD_NOT_READY, "c").body).has("detail") shouldBe false
    }
}
