package com.dynokore.gst.canonical.adapters.document

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class JacksonResultJsonTest {
    @Test
    fun `a held record serialises to the members of the 202 example of the canonical contract`() {
        val result =
            linkedMapOf<String, Any?>(
                "docUuid" to "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                "version" to 1,
                "scopeVerdict" to "SUBMITTABLE",
                "transactionDate" to "2026-03-20",
                "prescribedPeriodRef" to "",
                "reasons" to listOf(mapOf("code" to "PER-009", "field" to "/", "message" to "No period")),
            )
        val mapper = JsonMapper.builder().build()
        val tree = mapper.readTree(JacksonResultJson(mapper).toJson(result))
        tree.propertyNames().asSequence().toList() shouldBe
            listOf("docUuid", "version", "scopeVerdict", "transactionDate", "prescribedPeriodRef", "reasons")
        tree.get("version").isNumber shouldBe true
        listOf("docUuid", "scopeVerdict", "transactionDate", "prescribedPeriodRef").forEach {
            tree.get(it).isString shouldBe true
        }
        val reason = tree.get("reasons").get(0)
        reason.propertyNames().asSequence().toList() shouldBe listOf("code", "field", "message")
        reason.get("code").asString() shouldBe "PER-009"
    }
}
