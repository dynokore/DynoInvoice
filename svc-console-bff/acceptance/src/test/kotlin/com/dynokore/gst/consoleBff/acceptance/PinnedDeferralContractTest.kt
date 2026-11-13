package com.dynokore.gst.consoleBff.acceptance

import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/** The pinned console-bff contract carries SRT-005 on the deferral and override operations (contract-first). */
class PinnedDeferralContractTest {
    private val openapi = File(File(System.getProperty("gst.contract.dir")), "openapi.yaml").readText()

    private fun operations(): Map<String, Map<*, *>> {
        val paths = (Yaml().load<Map<String, Any?>>(openapi)["paths"] as? Map<*, *>).orEmpty()
        return paths.values
            .filterIsInstance<Map<*, *>>()
            .flatMap { item -> item.values.filterIsInstance<Map<*, *>>() }
            .mapNotNull { op -> (op["operationId"] as? String)?.let { it to op } }
            .toMap()
    }

    @Test
    fun `INV-AC-21143 the pinned contract carries SRT-005 on the three operations and 5000 ms on overrideException`() {
        val ops = operations()
        listOf("deferDocument", "undeferDocument", "overrideException").forEach { id ->
            val op = checkNotNull(ops[id]) { "operation $id is not in the pinned contract" }
            check(op["x-error-codes"] == listOf("SRT-005")) { "x-error-codes of $id is ${op["x-error-codes"]}" }
        }
        check(ops.getValue("overrideException")["x-timeout"] == 5000) {
            "x-timeout of overrideException is ${ops.getValue("overrideException")["x-timeout"]}"
        }
        // getExceptions is 3000 in the same document at the first pin bump (9eba697c).
        check(ops.getValue("getExceptions")["x-timeout"] == 3000) {
            "x-timeout of getExceptions is ${ops.getValue("getExceptions")["x-timeout"]}"
        }
        val tsv =
            File(File(System.getProperty("gst.root.dir")), "operations.tsv")
                .readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split('\t') }
        val tsvIds = tsv.filter { it[0] == "operation" }.map { it[1] }.toSet()
        val differing = (tsvIds - ops.keys) + (ops.keys - tsvIds)
        check(differing.isEmpty()) { "operation ids differ from operations.tsv: $differing" }
    }
}
