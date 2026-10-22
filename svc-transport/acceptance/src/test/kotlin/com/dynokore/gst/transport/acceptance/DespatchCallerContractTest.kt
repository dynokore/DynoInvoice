package com.dynokore.gst.transport.acceptance

import com.dynokore.gst.transport.adapters.http.DespatchController
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * INV-Q-8339 D, INV-Q-8387: the calling service of the three despatch operations is the one their `x-sync-edge`
 * names, enforced by the runtime from the generated `sync-edge-callers.tsv`; the service writes no caller name.
 */
class DespatchCallerContractTest {
    private val operations = listOf("submitPayload", "submitPackage", "sendNetworkDocument")

    private fun contractOperations(): Map<String, Triple<String, String, String>> {
        val openapi =
            Yaml().load<Map<String, Any?>>(
                File(File(System.getProperty("gst.contract.dir")), "openapi.yaml").readText(),
            )
        val found = mutableMapOf<String, Triple<String, String, String>>()
        (openapi["paths"] as Map<*, *>).forEach { (path, item) ->
            (item as Map<*, *>).forEach { (method, operation) ->
                val op = operation as? Map<*, *> ?: return@forEach
                val id = op["operationId"] as? String ?: return@forEach
                val edge = op["x-sync-edge"] as? String ?: return@forEach
                found[id] = Triple(method.toString().uppercase(), path.toString(), edge)
            }
        }
        return found
    }

    private fun generatedLines(): List<List<String>> =
        checkNotNull(javaClass.classLoader.getResourceAsStream("META-INF/gst/sync-edge-callers.tsv")) {
            "the transport contract jar has no sync-edge-callers.tsv"
        }.bufferedReader()
            .readLines()
            .map { it.split('\t') }

    @Test
    fun `INV-Q-8387 each despatch operation names submission-svc in x-sync-edge and the generated line says so`() {
        val contract = contractOperations()
        val lines = generatedLines()
        for (id in operations) {
            val (method, path, edge) = contract.getValue(id)
            edge shouldBe "submission-svc->transport-svc"
            lines.filter { it[0] == method && it[1] == path }.map { it[2] }.shouldContainExactly(
                edge.substringBefore("->"),
            )
        }
    }

    @Test
    fun `INV-Q-8387 the controller holds no authority replica and no entity set, and writes no caller name`() {
        val parameters =
            DespatchController::class.java.constructors
                .single()
                .parameterTypes
                .map { it.name }
        parameters.none { "replica" in it || "Authority" in it || "Clock" in it } shouldBe true
        val source = File(File(System.getProperty("gst.root.dir")), SOURCE).readText()
        listOf("AuthorityReplica", "AuthorizationRequest", "entitySetCurrent", ".entities", "decide(", "submission-svc")
            .filter { it in source.lineSequence().filterNot { l -> l.trimStart().startsWith("*") }.joinToString("\n") }
            .shouldContainExactly()
    }

    private companion object {
        const val SOURCE =
            "transport-adapters/src/main/kotlin/com/dynokore/gst/transport/adapters/http/DespatchController.kt"
    }
}
