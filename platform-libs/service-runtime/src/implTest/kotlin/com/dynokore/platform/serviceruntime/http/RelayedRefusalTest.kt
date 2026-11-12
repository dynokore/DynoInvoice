package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.serviceruntime.contract.ContractValidator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/** INV-AC-21193 (INV-T-21194): `Problems.renderRelayed` and `RelayedRefusal`. */
class RelayedRefusalTest {
    private val mapper = ObjectMapper()
    private val contracts = ContractValidator()

    private fun refusal(
        status: Int = 422,
        code: String = "PAY-017",
        title: String = "Payload refused",
        severity: String? = "BLOCK",
        disposition: String? = "MANUAL",
        owner: String? = "Tax operations",
        detail: String? = "why",
    ) = RelayedRefusal(status, code, title, severity, disposition, owner, detail)

    @Test
    fun `INV-AC-21193 the PAY-017 refusal is relayed with the owner's status and members and validates`() {
        val response = Problems.renderRelayed(refusal(), "req-1")
        response.status shouldBe 422
        response.headers shouldBe mapOf("Content-Type" to "application/problem+json")
        response.headers.containsKey("Retry-After") shouldBe false
        val body = mapper.readTree(response.body)
        contracts.schemaErrors("service-runtime/schemas/error-envelope.schema.json", body).shouldBeEmpty()
        body.get("type").asString() shouldBe Problems.typeOf("PAY-017")
        body.get("title").asString() shouldBe "Payload refused"
        body.get("status").asInt() shouldBe 422
        body.get("code").asString() shouldBe "PAY-017"
        body.get("severity").asString() shouldBe "BLOCK"
        body.get("disposition").asString() shouldBe "MANUAL"
        body.get("remediationOwner").asString() shouldBe "Tax operations"
        body.get("detail").asString() shouldBe "why"
        body.get("correlationId").asString() shouldBe "req-1"
    }

    @Test
    fun `INV-AC-21193 the SRT-005 refusal without optional members omits them`() {
        val response = Problems.renderRelayed(refusal(404, "SRT-005", "Not found", null, null, null, null), "req-1")
        response.status shouldBe 404
        val body = mapper.readTree(response.body)
        body.propertyNames().asSequence().toSet() shouldBe setOf("type", "title", "status", "code", "correlationId")
        body.get("code").asString() shouldBe "SRT-005"
        body.get("correlationId").asString() shouldBe "req-1"
        contracts.schemaErrors("service-runtime/schemas/error-envelope.schema.json", body).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-21193 a malformed refusal throws IllegalArgumentException`() {
        listOf(
            { refusal(status = 200) },
            { refusal(status = 399) },
            { refusal(status = 429) },
            { refusal(status = 500) },
            { refusal(code = "PAY017") },
            { refusal(code = "pay-017") },
            { refusal(code = "SRT-1") },
            { refusal(code = "") },
            { refusal(title = " ") },
            { refusal(severity = "FATAL") },
            { refusal(disposition = " ") },
            { refusal(owner = " ") },
        ).forEach { make ->
            val thrown = runCatching { make() }.exceptionOrNull()
            (thrown is IllegalArgumentException) shouldBe true
        }
        shouldThrow<IllegalArgumentException> { refusal(status = 500) }
    }

    @Test
    fun `INV-AC-21193 the family renderers are unchanged`() {
        val response = Problems.renderPeriod(PeriodErrorCode.PERIOD_NOT_READY, "c")
        response.status shouldBe 422
        response.headers shouldBe mapOf("Content-Type" to "application/problem+json")
        response.body shouldBe
            "{\"type\":\"https://errors.invoice.internal/PER-001\"," +
            "\"title\":\"${PeriodErrorCode.PERIOD_NOT_READY.title}\"," +
            "\"status\":422,\"correlationId\":\"c\",\"code\":\"PER-001\",\"severity\":\"BLOCK\"," +
            "\"disposition\":\"OVERRIDABLE\",\"remediationOwner\":\"Tax operations\"}"
    }
}
