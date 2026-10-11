package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.serviceruntime.contract.ContractValidator
import com.dynokore.platform.serviceruntime.jobs.JobErrorCode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.ObjectMapper

/**
 * Every closed service-runtime case has its SRT catalogue row (owner ruling INV-Q-3720, INV-REQ-827 b3; the rows
 * of INV-T-3742 in the pinned platform-contracts `catalogue/errors/error-codes.yaml`) and renders as an error
 * envelope with that code: no service-runtime error is code-less.
 */
class ProblemsTest {
    private val mapper = ObjectMapper()
    private val contracts = ContractValidator()

    @Suppress("UNCHECKED_CAST")
    private val catalogue: Map<String, Map<String, Any>> =
        javaClass.getResource("/contracts/catalogue/errors/error-codes.yaml")!!.readText().let { text ->
            ((Yaml().load<Map<String, Any>>(text)["error_codes"]) as List<Map<String, Any>>)
                .filter { (it["code"] as String).startsWith("SRT-") }
                .associateBy { it["code"] as String }
        }

    @Test
    fun `the closed cases are exactly the catalogue's SRT rows, at their HTTP statuses`() {
        ClosedErrorCode.entries.map { it.code } shouldBe catalogue.keys.sorted()
        ClosedErrorCode.entries.associate { it.code to it.httpStatus } shouldBe
            mapOf(
                "SRT-001" to 400,
                "SRT-002" to 400,
                "SRT-003" to 401,
                "SRT-004" to 403,
                "SRT-005" to 404,
                "SRT-006" to 405,
                "SRT-007" to 409,
                "SRT-008" to 409,
                "SRT-009" to 422,
                "SRT-010" to 500,
                "SRT-011" to 503,
            )
        ClosedErrorCode.entries.forEach { code ->
            val row = catalogue.getValue(code.code)
            (row["condition"] as String).replace(Regex("\\s+"), " ").contains("HTTP ${code.httpStatus}") shouldBe true
            row["severity"] shouldBe "BLOCK"
            row["owner"] shouldBe code.remediationOwner
            row["disposition"] shouldBe code.disposition
            code.disposition shouldBe "TERMINAL" // INV-Q-3754 for SRT-007 and SRT-008
        }
    }

    @Test
    fun `every closed case renders with its code, and a server error carries no detail`() {
        ClosedErrorCode.entries.forEach { code ->
            val response = Problems.closed(code, "corr-1", "what went wrong")
            response.status shouldBe code.httpStatus
            val body = mapper.readTree(response.body)
            contracts.schemaErrors("service-runtime/schemas/error-envelope.schema.json", body).shouldBeEmpty()
            body.get("code").asString() shouldBe code.code
            body.get("type").asString() shouldBe "https://errors.invoice.internal/${code.code}"
            body.get("severity").asString() shouldBe "BLOCK"
            body.get("disposition").asString() shouldBe code.disposition
            body.get("remediationOwner").asString() shouldBe "Platform"
            body.has("detail") shouldBe (code.httpStatus < 500)
        }
    }

    @Test
    fun `every coded response carries its code's documentation URI as type, never about-blank (API-20, INV-Q-3796)`() {
        JobErrorCode.entries.forEach { code ->
            val response = Problems.job(code, "corr-1")
            response.status shouldBe code.httpStatus
            val body = mapper.readTree(response.body)
            contracts.schemaErrors("service-runtime/schemas/error-envelope.schema.json", body).shouldBeEmpty()
            body.get("code").asString() shouldBe code.code
            body.get("type").asString() shouldBe "https://errors.invoice.internal/${code.code}"
        }
        Problems.typeOf("SRT-010") shouldBe "https://errors.invoice.internal/SRT-010"
    }
}
