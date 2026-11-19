package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.serviceruntime.contract.ContractValidator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.ObjectMapper

/**
 * PER-001 (coordinator ruling of round z, 2026-11-12; INV-T-20923), PER-017 and PER-018 (INV-T-25147) against the
 * pinned catalogue snapshot (`src/implTest/resources/contracts/catalogue/errors/error-codes.yaml`): the snapshot
 * holds exactly one row for each of the three codes, the enum has exactly those three entries, and their fields
 * equal the rows'. Rows of other codes are not compared.
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
                .filter { it["code"].toString() in listOf("PER-001", "PER-017", "PER-018") }
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
    fun `the snapshot holds one row each of PER-001, PER-017 and PER-018 and the enum exactly three entries`() {
        rows.size shouldBe 3
        rows.map { it.code }.sorted() shouldBe listOf("PER-001", "PER-017", "PER-018")
        PeriodErrorCode.entries.size shouldBe 3
        PeriodErrorCode.Owner.entries.size shouldBe 1
        PeriodErrorCode.entries.map { it.code } shouldBe listOf("PER-001", "PER-017", "PER-018")
    }

    @Test
    fun `INV-AC-20922 PER-001 is the row's code, title, severity, disposition and owner with the declared 422`() {
        val row = rows.single { it.code == "PER-001" }
        val entry = PeriodErrorCode.PERIOD_NOT_READY
        PeriodErrorCode.entries.first() shouldBe entry
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
        val row = rows.single { it.code == "PER-001" }
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

    @Test
    @DisplayName(
        "INV-AC-25146 PER-017 and PER-018 are the rows' code, title, severity, disposition and owner " +
            "with the declared 403 and 409",
    )
    fun `INV-AC-25146 entries equal rows`() {
        PeriodErrorCode.entries.map { it.name } shouldBe
            listOf("PERIOD_NOT_READY", "ATTESTATION_SEPARATION_OF_DUTIES", "ATTESTATION_UNEXPLAINED_RESIDUAL")
        val expected =
            mapOf(
                PeriodErrorCode.ATTESTATION_SEPARATION_OF_DUTIES to ("PER-017" to 403),
                PeriodErrorCode.ATTESTATION_UNEXPLAINED_RESIDUAL to ("PER-018" to 409),
            )
        expected.forEach { (entry, codeAndStatus) ->
            val row = rows.single { it.code == codeAndStatus.first }
            entry.code shouldBe row.code
            entry.title shouldBe row.condition
            entry.severity shouldBe "BLOCK"
            entry.severity shouldBe row.severity
            entry.disposition shouldBe "MANUAL"
            entry.disposition shouldBe row.disposition
            entry.owner.text shouldBe "Tax operations"
            entry.owner.text shouldBe row.owner
            entry.httpStatus shouldBe codeAndStatus.second
        }
        PeriodErrorCode.ATTESTATION_SEPARATION_OF_DUTIES.httpStatus shouldBe HttpStatusCodes.FORBIDDEN
        PeriodErrorCode.ATTESTATION_UNEXPLAINED_RESIDUAL.httpStatus shouldBe HttpStatusCodes.CONFLICT
    }

    @Test
    @DisplayName(
        "INV-AC-25146 renderPeriod gives the declared status and an envelope that validates for each of " +
            "PER-017 and PER-018",
    )
    fun `INV-AC-25146 renderPeriod envelopes`() {
        listOf(
            PeriodErrorCode.ATTESTATION_SEPARATION_OF_DUTIES to 403,
            PeriodErrorCode.ATTESTATION_UNEXPLAINED_RESIDUAL to 409,
        ).forEach { (entry, status) ->
            val row = rows.single { it.code == entry.code }
            val response = Problems.renderPeriod(entry, "corr-1", "what went wrong")
            response.status shouldBe status
            response.headers shouldBe mapOf("Content-Type" to "application/problem+json")
            response.headers.containsKey("Retry-After") shouldBe false
            val body = mapper.readTree(response.body)
            contracts.schemaErrors("service-runtime/schemas/error-envelope.schema.json", body).shouldBeEmpty()
            body.get("type").asString() shouldBe Problems.typeOf(row.code)
            body.get("code").asString() shouldBe row.code
            body.get("title").asString() shouldBe row.condition
            body.get("status").asInt() shouldBe status
            body.get("severity").asString() shouldBe row.severity
            body.get("disposition").asString() shouldBe row.disposition
            body.get("remediationOwner").asString() shouldBe row.owner
            body.get("correlationId").asString() shouldBe "corr-1"
            body.get("detail").asString() shouldBe "what went wrong"
        }
    }
}
