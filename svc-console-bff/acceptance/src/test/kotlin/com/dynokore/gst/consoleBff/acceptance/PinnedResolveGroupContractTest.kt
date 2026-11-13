package com.dynokore.gst.consoleBff.acceptance

import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/** The pinned contract holds resolveGroup with its request, result, error codes and timeout; operations.tsv lists it. */
class PinnedResolveGroupContractTest {
    private val consoleDir = File(System.getProperty("gst.contract.dir"))
    private val console = document("console-bff")

    private class Op(
        val path: String,
        val method: String,
        val body: Map<*, *>,
    )

    // Staged layout: one directory per service beside console-bff, each holding the bundle version.
    private fun document(service: String): Map<*, *> {
        val dir = File(consoleDir.parentFile.parentFile, service)
        val file = File(dir, "${consoleDir.name}/openapi.yaml")
        check(file.isFile) { "the pinned bundle does not stage $service/openapi.yaml at ${file.path}" }
        return Yaml().load<Map<*, *>>(file.readText())
    }

    private fun Map<*, *>.operation(id: String): Op {
        val found =
            (this["paths"] as Map<*, *>).entries.flatMap { (path, item) ->
                (item as Map<*, *>)
                    .entries
                    .filter { (it.value as? Map<*, *>)?.get("operationId") == id }
                    .map { Op(path as String, (it.key as String).uppercase(), it.value as Map<*, *>) }
            }
        return checkNotNull(found.singleOrNull()) { "operation $id is not (once) in the pinned contract" }
    }

    private fun Map<*, *>.schema(name: String): Map<*, *> =
        checkNotNull(((this["components"] as Map<*, *>)["schemas"] as Map<*, *>)[name] as? Map<*, *>) {
            "schema $name is absent"
        }

    private fun Op.schemaRef(status: String): Any? {
        val response = (body["responses"] as Map<*, *>)[status] as Map<*, *>
        val json = (response["content"] as Map<*, *>)["application/json"] as Map<*, *>
        return (json["schema"] as Map<*, *>)["\$ref"]
    }

    private fun requireEq(
        what: String,
        actual: Any?,
        expected: Any?,
    ) {
        check(actual == expected) { "$what is $actual, expected $expected" }
    }

    private fun props(schema: Map<*, *>) = (schema["properties"] as Map<*, *>)

    @Test
    fun `INV-AC-21303 resolveGroup is pinned with its request, result, codes, timeout and table row`() {
        val op = console.operation("resolveGroup")
        requireEq("method", op.method, "POST")
        requireEq("path", op.path, "/bff/entities/{entityRef}/group-resolutions")
        requireEq("responses", (op.body["responses"] as Map<*, *>).keys, setOf("200", "409", "413", "default"))
        requireEq(
            "x-error-codes",
            op.body["x-error-codes"],
            listOf("MDM-001", "MDM-002", "MDM-003", "MDM-004", "MDM-006", "SRT-001", "SRT-004", "SRT-014", "SRT-015"),
        )
        requireEq("x-timeout", op.body["x-timeout"], 5000)

        val request = console.schema("ResolveGroupRequest")
        val fields = setOf("errorCode", "counterpartyRef", "docUuids", "resolvedIdentifiers", "evidenceRef")
        requireEq("ResolveGroupRequest properties", props(request).keys, fields)
        requireEq("ResolveGroupRequest required", (request["required"] as List<*>).toSet(), fields)
        val docUuids = props(request)["docUuids"] as Map<*, *>
        requireEq("docUuids.minItems", docUuids["minItems"], 1)
        requireEq("docUuids.maxItems", docUuids["maxItems"], 5000)

        val result = console.schema("ResolveGroupResult")
        val counts = setOf("resolvedCount", "unchangedCount")
        requireEq("ResolveGroupResult properties", props(result).keys, counts)
        requireEq("ResolveGroupResult required", (result["required"] as List<*>).toSet(), counts)

        val rows =
            File(System.getProperty("gst.root.dir"), "operations.tsv").readLines().drop(1).map { it.split('\t') }.filter { it.getOrNull(1) == "resolveGroup" }
        requireEq("operations.tsv resolveGroup rows", rows.size, 1)
        requireEq("operations.tsv row", rows.single(), listOf("operation", "resolveGroup", "UNASSIGNED"))
    }
}
