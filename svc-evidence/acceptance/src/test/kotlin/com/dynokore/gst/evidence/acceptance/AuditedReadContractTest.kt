package com.dynokore.gst.evidence.acceptance

import com.dynokore.gst.evidence.domain.auditedread.AuditedOperation
import com.dynokore.gst.evidence.domain.auditedread.ReverseLookupParameter
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/** The audited-read enums against the pinned `evidence/openapi.yaml` staged by evidence-contract (INV-Q-7207 A). */
class AuditedReadContractTest {
    private val spec: Map<*, *> =
        Yaml().load(File(System.getProperty("gst.contract.dir"), "openapi.yaml").readText())

    private fun operation(id: String): Map<*, *> =
        (spec["paths"] as Map<*, *>)
            .values
            .filterIsInstance<Map<*, *>>()
            .flatMap { it.values }
            .filterIsInstance<Map<*, *>>()
            .single { it["operationId"] == id }

    @Test
    fun `the audited operation values are four operation ids of the contract`() {
        val ids = AuditedOperation.entries.map { it.operationId }
        ids.toSet() shouldBe
            setOf("getAcknowledgementChain", "reverseLookup", "createAuditPackExport", "getAuditPackExport")
        ids.forEach { operation(it) }
        OperationsGate.contractOperations(
            File(System.getProperty("gst.contract.dir"), "openapi.yaml").readText(),
        ) shouldContainAll
            ids
        AuditedOperation.entries.size shouldBe 4
        listOf("getEvidenceRetrieval", "exportWorkPaper", "deleteAtRetentionEnd").forEach { (it in ids) shouldBe false }
    }

    @Test
    fun `the reverse lookup parameters equal the contract query parameters minus limit and cursor`() {
        val declared =
            (operation("reverseLookup")["parameters"] as List<*>).map { parameter ->
                val p = parameter as Map<*, *>
                check("\$ref" !in p) { "reverseLookup: a \$ref parameter is not resolved by this test" }
                check(p["in"] == "query") { "reverseLookup: parameter ${p["name"]} is not a query parameter" }
                p["name"] as String
            }
        ReverseLookupParameter.entries.map { it.parameterName }.toSet() shouldBe
            declared.toSet() - setOf("limit", "cursor")
    }

    @Test
    fun `the acknowledgement id maximum length in the contract equals the scope limit`() {
        val ackId =
            (operation("getAcknowledgementChain")["parameters"] as List<*>)
                .map { it as Map<*, *> }
                .single { it["name"] == "ackId" }
        ((ackId["schema"] as Map<*, *>)["maxLength"]) shouldBe
            com.dynokore.gst.evidence.domain.auditedread.AuditedReadScope.ACK_ID_MAX_LENGTH
    }
}
