package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.serviceruntime.contract.ContractValidator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.ObjectMapper

/**
 * The tenancy refusals against the pinned catalogue snapshot (`src/implTest/resources/contracts/catalogue/errors/
 * error-codes.yaml`, README there): the three codes equal the snapshot's TNC rows, and for each row the title is the
 * condition before its first ": ", the status the "HTTP nnn" of the condition and the owner the row's owner
 * (coordinator ruling INV-Q-7553 A). Each code renders as a 409 envelope that validates against the pinned schema.
 */
class TenancyErrorCodeTest {
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
            Yaml().load<Map<String, List<Map<String, Any?>>>>(text).getValue("error_codes").map {
                Row(
                    it["code"].toString(),
                    it["condition"].toString(),
                    it["severity"].toString(),
                    it["disposition"].toString(),
                    it["owner"].toString(),
                )
            }
        }.filter { it.code.startsWith("TNC-") }

    @Test
    fun `the codes equal the 4 TNC rows of the pinned catalogue snapshot, in order`() {
        rows.size shouldBe 4
        TenancyErrorCode.entries.map { it.code } shouldBe rows.map { it.code }
    }

    @Test
    fun `the title, status and owner of each code are the snapshot row's, and the titles are distinct`() {
        val byCode = TenancyErrorCode.entries.associateBy { it.code }
        rows.forEach { row ->
            val code = byCode.getValue(row.code)
            (": " in row.condition) shouldBe true
            code.title shouldBe row.condition.substringBefore(": ")
            code.httpStatus shouldBe Regex("""\(HTTP (\d{3})""").find(row.condition)!!.groupValues[1].toInt()
            code.owner.text shouldBe row.owner
            row.severity shouldBe "BLOCK"
            row.disposition shouldBe "TERMINAL"
        }
        TenancyErrorCode.entries
            .map { it.title }
            .toSet()
            .size shouldBe 4
    }

    @Test
    fun `renderTenancy gives each code a 409 envelope that validates against the pinned schema`() {
        TenancyErrorCode.entries.forEach { code ->
            val response = Problems.renderTenancy(code, "corr-1", "what went wrong")
            response.status shouldBe 409
            response.headers shouldBe mapOf("Content-Type" to "application/problem+json")
            val body = mapper.readTree(response.body)
            contracts.schemaErrors("service-runtime/schemas/error-envelope.schema.json", body).shouldBeEmpty()
            body.get("code").asString() shouldBe code.code
            body.get("title").asString() shouldBe code.title
            body.get("status").asInt() shouldBe 409
            body.get("severity").asString() shouldBe "BLOCK"
            body.get("disposition").asString() shouldBe "TERMINAL"
            body.get("remediationOwner").asString() shouldBe code.owner.text
            body.get("correlationId").asString() shouldBe "corr-1"
            body.get("detail").asString() shouldBe "what went wrong"
        }
        val withoutDetail = Problems.renderTenancy(TenancyErrorCode.SHORT_CODE_FIXED, "c")
        mapper.readTree(withoutDetail.body).has("detail") shouldBe false
    }
}
