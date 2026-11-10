package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.serviceruntime.contract.ContractValidator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.ObjectMapper

/**
 * The tenancy refusals against the pinned catalogue snapshot (`src/implTest/resources/contracts/catalogue/errors/
 * error-codes.yaml`, README there): the codes equal the snapshot's TNC rows, and for each row the title is the
 * condition before its first ": ", the status the "HTTP nnn" of the condition and the owner the row's owner
 * (coordinator ruling INV-Q-7553 A). Each code renders as an envelope of its own status that validates against the pinned schema.
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
    fun `the codes equal the 13 TNC rows of the pinned catalogue snapshot, in order`() {
        rows.size shouldBe 13
        TenancyErrorCode.entries.map { it.code } shouldBe rows.map { it.code }
    }

    @Test
    fun `the title, status and owner of each code are the snapshot row's, and the titles are the snapshot's`() {
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
            .toSet() shouldBe rows.map { it.condition.substringBefore(": ") }.toSet()
    }

    @Test
    fun `renderTenancy gives each code an envelope of its status that validates against the pinned schema`() {
        TenancyErrorCode.entries.forEach { code ->
            val response = Problems.renderTenancy(code, "corr-1", "what went wrong")
            response.status shouldBe code.httpStatus
            response.headers shouldBe mapOf("Content-Type" to "application/problem+json")
            val body = mapper.readTree(response.body)
            contracts.schemaErrors("service-runtime/schemas/error-envelope.schema.json", body).shouldBeEmpty()
            body.get("code").asString() shouldBe code.code
            body.get("title").asString() shouldBe code.title
            body.get("status").asInt() shouldBe code.httpStatus
            body.get("severity").asString() shouldBe "BLOCK"
            body.get("disposition").asString() shouldBe "TERMINAL"
            body.get("remediationOwner").asString() shouldBe code.owner.text
            body.get("correlationId").asString() shouldBe "corr-1"
            body.get("detail").asString() shouldBe "what went wrong"
        }
        val withoutDetail = Problems.renderTenancy(TenancyErrorCode.SHORT_CODE_FIXED, "c")
        mapper.readTree(withoutDetail.body).has("detail") shouldBe false
    }

    @Test
    fun `INV-AC-18808 MIGRATION_CUTOVER_CONFLICT is the catalogue row TNC-011 and renders as a 409 envelope`() {
        val row = rows.single { it.code == "TNC-011" }
        row.severity shouldBe "BLOCK"
        row.disposition shouldBe "TERMINAL"
        row.owner shouldBe "entity.admin"
        row.condition.contains("HTTP 409") shouldBe true
        val entry = TenancyErrorCode.MIGRATION_CUTOVER_CONFLICT
        entry.code shouldBe "TNC-011"
        entry.httpStatus shouldBe 409
        entry.title shouldBe "Migration cutover refused"
        entry.title shouldBe row.condition.substringBefore(": ")
        entry.owner.text shouldBe "entity.admin"
        entry.owner.text shouldBe row.owner
        val response = Problems.renderTenancy(entry, "corr-1")
        response.status shouldBe 409
        val body = mapper.readTree(response.body)
        contracts.schemaErrors("service-runtime/schemas/error-envelope.schema.json", body).shouldBeEmpty()
        body.get("code").asString() shouldBe "TNC-011"
        body.get("type").asString() shouldBe "https://errors.invoice.internal/TNC-011"
    }

    @Test
    fun `INV-AC-19378 TENANT_NOT_OPENABLE is the catalogue row TNC-012 and renders as a 409 envelope`() {
        val row = rows.single { it.code == "TNC-012" }
        row.severity shouldBe "BLOCK"
        row.disposition shouldBe "TERMINAL"
        row.owner shouldBe "Platform"
        row.condition.contains("HTTP 409") shouldBe true
        val entry = TenancyErrorCode.TENANT_NOT_OPENABLE
        entry.code shouldBe "TNC-012"
        entry.httpStatus shouldBe 409
        entry.title shouldBe "Tenant open refused"
        entry.title shouldBe row.condition.substringBefore(": ")
        entry.owner shouldBe TenancyErrorCode.Owner.PLATFORM
        entry.owner.text shouldBe row.owner
        val response = Problems.renderTenancy(entry, "corr-1")
        response.status shouldBe 409
        val body = mapper.readTree(response.body)
        contracts.schemaErrors("service-runtime/schemas/error-envelope.schema.json", body).shouldBeEmpty()
        body.get("code").asString() shouldBe "TNC-012"
        body.get("type").asString() shouldBe "https://errors.invoice.internal/TNC-012"
    }

    @Test
    fun `INV-AC-19488 ELECTION_CHANGE_PENDING is the catalogue row TNC-013 and renders as a 409 envelope`() {
        val row = rows.single { it.code == "TNC-013" }
        row.severity shouldBe "BLOCK"
        row.disposition shouldBe "TERMINAL"
        row.owner shouldBe "entity.admin"
        row.condition.contains("HTTP 409") shouldBe true
        val entry = TenancyErrorCode.ELECTION_CHANGE_PENDING
        entry.code shouldBe "TNC-013"
        entry.httpStatus shouldBe 409
        entry.title shouldBe "Election change refused"
        entry.title shouldBe row.condition.substringBefore(": ")
        entry.owner shouldBe TenancyErrorCode.Owner.ENTITY_ADMIN
        entry.owner.text shouldBe row.owner
        val response = Problems.renderTenancy(entry, "corr-1")
        response.status shouldBe 409
        val body = mapper.readTree(response.body)
        contracts.schemaErrors("service-runtime/schemas/error-envelope.schema.json", body).shouldBeEmpty()
        body.get("code").asString() shouldBe "TNC-013"
        body.get("type").asString() shouldBe "https://errors.invoice.internal/TNC-013"
    }
}
