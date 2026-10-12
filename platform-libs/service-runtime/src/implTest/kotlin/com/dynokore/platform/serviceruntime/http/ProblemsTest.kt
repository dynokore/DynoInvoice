package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.serviceruntime.contract.ContractValidator
import com.dynokore.platform.serviceruntime.jobs.JobErrorCode
import io.kotest.assertions.throwables.shouldThrow
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
                "SRT-012" to 429,
            )
        ClosedErrorCode.entries.forEach { code ->
            val row = catalogue.getValue(code.code)
            (row["condition"] as String).replace(Regex("\\s+"), " ").contains("HTTP ${code.httpStatus}") shouldBe true
            row["severity"] shouldBe "BLOCK"
            row["owner"] shouldBe code.remediationOwner
            row["disposition"] shouldBe code.disposition
            code.disposition shouldBe "TERMINAL" // INV-Q-3754 for SRT-007 and SRT-008, INV-Q-4059 for SRT-012
        }
    }

    @Test
    fun `SRT-012 matches its gst-catalogue 1_15_0 row, BLOCK and TERMINAL like SRT-011, retried after Retry-After`() {
        val row = catalogue.getValue("SRT-012")
        val code = ClosedErrorCode.RATE_LIMITED
        code.code shouldBe "SRT-012"
        code.httpStatus shouldBe 429
        code.title shouldBe "Protective rate limit exceeded"
        (row["condition"] as String).startsWith("Protective rate limit exceeded; retry after Retry-After") shouldBe true
        row["severity"] shouldBe "BLOCK"
        row["disposition"] shouldBe "TERMINAL"
        row["owner"] shouldBe "Platform"
        code.disposition shouldBe ClosedErrorCode.ISSUER_UNAVAILABLE.disposition
    }

    @Test
    fun `render gives the gateway's denials the service envelope, and a 429 carries Retry-After (INV-T-4056)`() {
        val denials =
            listOf(
                Problems.render(ClosedErrorCode.UNAUTHENTICATED, "corr-401"),
                Problems.render(ClosedErrorCode.ISSUER_UNAVAILABLE, "corr-503"),
                Problems.render(ClosedErrorCode.RATE_LIMITED, "corr-429", retryAfterSeconds = 30),
            )
        denials.map { it.status } shouldBe listOf(401, 503, 429)
        denials.zip(listOf("SRT-003", "SRT-011", "SRT-012")).forEach { (response, code) ->
            val body = mapper.readTree(response.body)
            contracts.schemaErrors("service-runtime/schemas/error-envelope.schema.json", body).shouldBeEmpty()
            body.get("code").asString() shouldBe code
            body.get("type").asString() shouldBe "https://errors.invoice.internal/$code"
            body.get("severity").asString() shouldBe "BLOCK"
            body.get("disposition").asString() shouldBe "TERMINAL"
            body.get("correlationId").asString() shouldBe "corr-${response.status}"
            response.headers["Content-Type"] shouldBe "application/problem+json"
        }
        denials.map { it.headers["Retry-After"] } shouldBe listOf(null, null, "30")
        ClosedErrorCode.entries.filter { it.httpStatus != 429 }.forEach { code ->
            val rendered = Problems.render(code, "corr-1", "what went wrong")
            val closed = Problems.closed(code, "corr-1", "what went wrong")
            (rendered.status to rendered.body) shouldBe (closed.status to closed.body)
        }
    }

    @Test
    fun `render refuses a 429 without Retry-After and Retry-After on any other code`() {
        shouldThrow<IllegalArgumentException> { Problems.render(ClosedErrorCode.RATE_LIMITED, "corr-1") }
        shouldThrow<IllegalArgumentException> {
            Problems.render(ClosedErrorCode.RATE_LIMITED, "corr-1", retryAfterSeconds = -1)
        }
        shouldThrow<IllegalArgumentException> {
            Problems.render(ClosedErrorCode.ISSUER_UNAVAILABLE, "corr-1", retryAfterSeconds = 5)
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
