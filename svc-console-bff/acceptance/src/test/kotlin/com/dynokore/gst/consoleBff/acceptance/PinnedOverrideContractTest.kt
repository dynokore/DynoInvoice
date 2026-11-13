package com.dynokore.gst.consoleBff.acceptance

import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/** The third pin bump: 202 PendingOverride, the five overrideException codes and the two routed reads. */
class PinnedOverrideContractTest {
    private val consoleDir = File(System.getProperty("gst.contract.dir"))
    private val payload = document("payload")
    private val submission = document("submission")
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

    @Test
    fun `INV-AC-21180 overrideException has 202 PendingOverride, five codes and 5000 ms`() {
        val op = console.operation("overrideException")
        requireEq("responses", (op.body["responses"] as Map<*, *>).keys, setOf("200", "202", "default"))
        requireEq("202 schema", op.schemaRef("202"), "#/components/schemas/PendingOverride")
        requireEq(
            "x-error-codes",
            op.body["x-error-codes"],
            listOf("SRT-005", "SRT-004", "SRT-008", "PAY-017", "SRT-001"),
        )
        requireEq("x-timeout", op.body["x-timeout"], 5000)
        val pending = console.schema("PendingOverride")
        val fields = setOf("exceptionId", "reason", "requestedAt", "status")
        requireEq("PendingOverride properties", (pending["properties"] as Map<*, *>).keys, fields)
        requireEq("PendingOverride required", (pending["required"] as List<*>).toSet(), fields)
        val status = (pending["properties"] as Map<*, *>)["status"] as Map<*, *>
        requireEq("PendingOverride status.enum", status["enum"], listOf("PENDING"))
        requireEq("x-timeout of getExceptions", console.operation("getExceptions").body["x-timeout"], 3000)
    }

    @Test
    fun `INV-AC-21180 the two routed reads and the two override operations are in the staged documents`() {
        val version = payload.operation("getLatestDocumentVersion")
        requireEq("getLatestDocumentVersion", version.method, "GET")
        requireEq("path", version.path, "/internal/v1/payload/documents/{docUuid}/latest-version")
        requireEq("200 schema", version.schemaRef("200"), "#/components/schemas/LatestDocumentVersion")
        requireEq(
            "LatestDocumentVersion required",
            (payload.schema("LatestDocumentVersion")["required"] as List<*>).toSet(),
            setOf("docUuid", "version"),
        )
        val check = submission.operation("getLatestReadinessCheck")
        requireEq("getLatestReadinessCheck", check.method, "GET")
        requireEq(
            "path",
            check.path,
            "/internal/v1/entities/{entityRef}/periods/{periodRef}/readiness/latest-check",
        )
        requireEq("200 schema", check.schemaRef("200"), "#/components/schemas/ReadinessCheckRecord")
        requireEq(
            "overrideValidation path",
            payload.operation("overrideValidation").path,
            "/internal/v1/payload/documents/{docUuid}/versions/{version}/overrides",
        )
        requireEq(
            "overrideFilingGate path",
            submission.operation("overrideFilingGate").path,
            "/internal/v1/entities/{entityRef}/periods/{periodRef}/filing-gate-overrides",
        )
    }
}
