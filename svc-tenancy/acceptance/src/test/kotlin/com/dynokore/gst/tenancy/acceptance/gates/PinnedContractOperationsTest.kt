package com.dynokore.gst.tenancy.acceptance.gates

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

class PinnedContractOperationsTest {
    private val root = File(System.getProperty("gst.repo.root"))
    private val contractDir = File(System.getProperty("gst.contract.dir"))

    private fun tableRows(): List<List<String>> =
        File(root, OperationsGate.NAME)
            .readLines()
            .drop(1)
            .filter { it.isNotBlank() }
            .map { it.split('\t') }

    @Test
    fun `INV-AC-13969 every operation and channel of the pinned contract is listed once with its assigned task`() {
        val operations = OperationsGate.contractOperations(File(contractDir, "openapi.yaml").readText())
        val channels = OperationsGate.contractChannels(File(contractDir, "asyncapi.yaml").readText())
        val rows = tableRows()
        for ((kind, names) in listOf("operation" to operations, "channel" to channels)) {
            for (name in names) {
                val matching = rows.filter { it[0] == name && it[1] == kind }
                matching.size shouldBe 1
                matching.single()[2] shouldBe Assignments.of(kind)[name]
            }
        }
        listOf("loadCredential", "recordSampleApproval", "requestSampleCopy").forEach { name ->
            rows.single { it[0] == name && it[1] == "operation" }[2] shouldBe "INV-T-4932"
        }
    }
}
