package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.serviceruntime.contract.ContractValidator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.ObjectMapper

/**
 * MDM-005, MDM-007 and MDM-009 against the pinned catalogue snapshot (`src/implTest/resources/contracts/catalogue/errors/
 * error-codes.yaml`; INV-AC-16982): the snapshot holds exactly one row for each, the enum has exactly those two
 * entries, and each entry's code, title (the row's condition in full), owner and severity equal its row's
 * (coordinator rulings INV-Q-7886 A and INV-Q-16979 A). The status is the 422 that `resolveCounterparty` declares for
 * MDM-005 and the 409 that `confirmRegisterSuggestion` declares for MDM-007. Each rendered envelope validates
 * against the pinned schema.
 */
class MasterdataErrorCodeTest {
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
        }.filter { it.code == "MDM-005" || it.code == "MDM-007" || it.code == "MDM-009" }

    private val declaredStatus =
        mapOf(
            MasterdataErrorCode.IDENTITY_TRIPLE_NOT_CONFIGURED to 422,
            MasterdataErrorCode.COUNTERPARTY_GSTN_CONFLICT to 409,
            MasterdataErrorCode.IMPORT_DRY_RUN_REFUSED to 422,
        )

    private fun rowOf(entry: MasterdataErrorCode): Row = rows.single { it.code == entry.code }

    @Test
    fun `the snapshot holds one row each of MDM-005, MDM-007 and MDM-009 and the enum three entries`() {
        rows.size shouldBe 3
        rows.count { it.code == "MDM-005" } shouldBe 1
        rows.count { it.code == "MDM-007" } shouldBe 1
        rows.count { it.code == "MDM-009" } shouldBe 1
        MasterdataErrorCode.entries.size shouldBe 3
        MasterdataErrorCode.entries.toSet() shouldBe declaredStatus.keys
        MasterdataErrorCode.Owner.entries.size shouldBe 2
    }

    @Test
    fun `the code, title, owner, severity and status of each entry are the row's and the declared status`() {
        MasterdataErrorCode.entries.forEach { entry ->
            val row = rowOf(entry)
            entry.code shouldBe row.code
            entry.title shouldBe row.condition
            entry.owner.text shouldBe row.owner
            entry.severity shouldBe row.severity
            entry.httpStatus shouldBe declaredStatus.getValue(entry)
        }
        MasterdataErrorCode.IDENTITY_TRIPLE_NOT_CONFIGURED.code shouldBe "MDM-005"
        MasterdataErrorCode.IDENTITY_TRIPLE_NOT_CONFIGURED.severity shouldBe "BLOCK"
        MasterdataErrorCode.COUNTERPARTY_GSTN_CONFLICT.code shouldBe "MDM-007"
        MasterdataErrorCode.COUNTERPARTY_GSTN_CONFLICT.severity shouldBe "WARN"
    }

    @Test
    fun `renderMasterdata gives the entry's status with the row's severity and disposition and validates`() {
        MasterdataErrorCode.entries.forEach { code ->
            val row = rowOf(code)
            val status = declaredStatus.getValue(code)
            val response = Problems.renderMasterdata(code, "corr-1", "what went wrong")
            response.status shouldBe status
            response.headers shouldBe mapOf("Content-Type" to "application/problem+json")
            response.headers shouldBe Problems.renderTenancy(TenancyErrorCode.entries.first(), "corr-1").headers
            val body = mapper.readTree(response.body)
            contracts.schemaErrors("service-runtime/schemas/error-envelope.schema.json", body).shouldBeEmpty()
            body.get("code").asString() shouldBe row.code
            body.get("title").asString() shouldBe row.condition
            body.get("status").asInt() shouldBe status
            body.get("severity").asString() shouldBe row.severity
            body.get("disposition").asString() shouldBe row.disposition
            body.get("remediationOwner").asString() shouldBe row.owner
            body.get("correlationId").asString() shouldBe "corr-1"
            body.get("detail").asString() shouldBe "what went wrong"
            mapper.readTree(Problems.renderMasterdata(code, "c").body).has("detail") shouldBe false
        }
    }

    @Test
    fun `INV-AC-17299 IMPORT_DRY_RUN_REFUSED is MDM-009 at 422 equal to its row and renders a valid envelope`() {
        val entry = MasterdataErrorCode.IMPORT_DRY_RUN_REFUSED
        val row = rows.single { it.code == "MDM-009" }
        entry.code shouldBe "MDM-009"
        entry.httpStatus shouldBe 422
        entry.title shouldBe row.condition
        entry.owner shouldBe MasterdataErrorCode.Owner.FINANCE
        entry.owner.text shouldBe row.owner
        entry.severity shouldBe "BLOCK"
        entry.severity shouldBe row.severity
        row.disposition shouldBe "MANUAL"
        MasterdataErrorCode.entries.map { it.code }.toSet() shouldBe setOf("MDM-005", "MDM-007", "MDM-009")
        val response = Problems.renderMasterdata(entry, "corr-9", "dry run unknown")
        response.status shouldBe 422
        val body = mapper.readTree(response.body)
        contracts.schemaErrors("service-runtime/schemas/error-envelope.schema.json", body).shouldBeEmpty()
        body.get("type").asString() shouldBe "https://errors.invoice.internal/MDM-009"
        body.get("disposition").asString() shouldBe "MANUAL"
        body.get("remediationOwner").asString() shouldBe "Finance"
        body.get("severity").asString() shouldBe "BLOCK"
    }
}
