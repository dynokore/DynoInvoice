package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.configresolver.PolicyRefusal
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * The refusal mapping of setPolicy and acknowledgeControlRelaxation against the pinned catalogue snapshot
 * (`src/implTest/resources/contracts/catalogue/errors/error-codes.yaml`, README there): every [PolicyRefusal] maps to
 * exactly one POL code, the 24 codes equal the snapshot's POL rows, and for each row the title is the condition before
 * its first ": ", the status the "HTTP nnn" of the condition and the owner the row's owner (coordinator ruling
 * INV-Q-5237). A [PolicyRefusal] member added later does not compile in [PolicyErrorCode.of] and fails here.
 */
class PolicyErrorCodeTest {
    private class Row(
        val code: String,
        val condition: String,
        val severity: String,
        val disposition: String,
        val owner: String,
    )

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
        }.filter { it.code.startsWith("POL-") }

    @Test
    fun `every PolicyRefusal maps to exactly one POL code`() {
        val mapped = PolicyRefusal.entries.map { PolicyErrorCode.of(it) }
        mapped.toSet().size shouldBe PolicyRefusal.entries.size
        PolicyRefusal.entries.forEach { PolicyErrorCode.of(it).refusal shouldBe it }
        // the two codes that are not a PolicyRefusal: an unregistered key and a retired one
        PolicyErrorCode.entries.filter { it.refusal == null }.map { it.code } shouldBe listOf("POL-001", "POL-002")
        PolicyErrorCode.entries.size shouldBe PolicyRefusal.entries.size + 2
    }

    @Test
    fun `the 24 codes equal the 24 POL rows of the pinned catalogue snapshot`() {
        rows.size shouldBe 24
        PolicyErrorCode.entries.map { it.code } shouldBe rows.map { it.code }
    }

    @Test
    fun `the title, status and owner of each code are the snapshot row's, and the 24 titles are distinct`() {
        val byCode = PolicyErrorCode.entries.associateBy { it.code }
        rows.forEach { row ->
            val code = byCode.getValue(row.code)
            (": " in row.condition) shouldBe true
            code.title shouldBe row.condition.substringBefore(": ")
            code.httpStatus shouldBe Regex("""\(HTTP (\d{3})""").find(row.condition)!!.groupValues[1].toInt()
            code.owner.text shouldBe row.owner
            row.severity shouldBe "BLOCK"
            row.disposition shouldBe "TERMINAL"
        }
        PolicyErrorCode.entries
            .map { it.title }
            .toSet()
            .size shouldBe 24
    }
}
