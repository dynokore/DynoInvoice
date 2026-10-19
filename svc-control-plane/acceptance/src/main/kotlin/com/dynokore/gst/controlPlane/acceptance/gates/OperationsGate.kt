package com.dynokore.gst.controlPlane.acceptance.gates

import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * The contract gate (INV-T-4984): `operations.tsv` lists every operationId of the control-plane OpenAPI document and
 * every channel of its AsyncAPI document with the task that implements it. It fails when
 * - the contract holds an operationId or channel the table does not list (or lists twice);
 * - the table lists an operationId or channel the contract does not hold;
 * - a row's task is marked `done` while its operation still answers 501: the generated interface answers 501 for an
 *   operation no adapters class overrides, and an override that calls `notImplemented("<operationId>")` is an explicit
 *   stub; or a row is `pending` while the operation is implemented (a stale row);
 * - a row is malformed: not `kind`, `id`, `task`, `state` columns, an unknown kind or state, or a task that is not an
 *   `INV-T-<n>` key.
 *
 * Deny by default: anything the gate cannot classify is a finding.
 */
object OperationsGate {
    const val NAME = "operations.tsv"
    private val OVERRIDE = Regex("""\boverride\s+fun\s+(\w+)\s*\(""")
    private val STUB = Regex("""notImplemented\(\s*"([^"\\]+)"\s*\)""")
    private val TASK = Regex("INV-T-[0-9]+")
    private val HEADER = listOf("kind", "id", "task", "state")

    fun contractOperations(openapi: String): Set<String> {
        val root = Yaml().load<Map<String, Any?>>(openapi)
        val paths = root["paths"] as? Map<*, *> ?: return emptySet()
        return paths.values
            .filterIsInstance<Map<*, *>>()
            .flatMap { item -> item.values.filterIsInstance<Map<*, *>>().mapNotNull { it["operationId"] as? String } }
            .toSet()
    }

    /** The channel keys of the AsyncAPI document (the channel id of the table). */
    fun contractChannels(asyncapi: String): Set<String> {
        val root = Yaml().load<Map<String, Any?>>(asyncapi)
        return (root["channels"] as? Map<*, *>)
            ?.keys
            ?.filterIsInstance<String>()
            ?.toSet()
            .orEmpty()
    }

    /** The functions the adapters' main sources override (an operation is implemented only if one overrides it). */
    fun overrides(adapterSources: List<File>): Set<String> = matches(adapterSources, OVERRIDE)

    /** The operationIds the adapters' main sources explicitly answer 501 for. */
    fun stubs(adapterSources: List<File>): Set<String> = matches(adapterSources, STUB)

    private fun matches(
        files: List<File>,
        pattern: Regex,
    ): Set<String> =
        files
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { pattern.findAll(SourceText.stripComments(it.readText())).map { m -> m.groupValues[1] }.toList() }
            .toSet()

    /** What the code answers: which operations are overridden and which of those call `notImplemented`. */
    data class Code(
        val implemented: Set<String>,
        val stubbed: Set<String>,
    )

    fun check(
        openapi: String,
        asyncapi: String,
        table: String?,
        code: Code,
    ): List<Finding> {
        val parsed = table?.let(Tsv::rows) ?: return listOf(Finding(NAME, NAME, "the table is missing or empty"))
        val (header, rows) = parsed
        val operations = contractOperations(openapi)
        val channels = contractChannels(asyncapi)
        val findings = ArrayList<Finding>()
        if (header != HEADER) findings += Finding(NAME, "header", "expected columns $HEADER, found $header")
        val seen = HashSet<List<String>>()
        for (row in rows) {
            findings +=
                if (row.size == HEADER.size &&
                    row.none { it.isEmpty() }
                ) {
                    rowFindings(row, seen, operations, channels, code)
                } else {
                    listOf(malformed(row))
                }
        }
        val listed = rows.filter { it.size == HEADER.size }.map { it[0] to it[1] }.toSet()
        findings += unlisted("operation", operations, listed, "operationId")
        findings += unlisted("channel", channels, listed, "channel")
        findings +=
            code.stubbed.filter { it !in operations }.sorted().map {
                Finding(NAME, it, "a 501 stub names an operationId the contract does not hold")
            }
        return findings
    }

    private fun malformed(row: List<String>) =
        Finding(NAME, row.joinToString(" "), "a row has the four columns $HEADER, all filled")

    private fun unlisted(
        kind: String,
        contract: Set<String>,
        listed: Set<Pair<String, String>>,
        noun: String,
    ) = contract.filter { kind to it !in listed }.sorted().map {
        Finding(NAME, it, "the contract holds this $noun but the table does not list it")
    }

    private fun rowFindings(
        row: List<String>,
        seen: MutableSet<List<String>>,
        operations: Set<String>,
        channels: Set<String>,
        code: Code,
    ): List<Finding> {
        val kind = row[0]
        val id = row[1]
        val task = row[2]
        val state = row[3]
        val out = ArrayList<Finding>()
        if (!seen.add(listOf(kind, id))) out += Finding(NAME, id, "listed twice")
        if (!TASK.matches(task)) out += Finding(NAME, id, "task '$task' is not an INV-T-<n> key")
        if (state != "done" && state != "pending") out += Finding(NAME, id, "state '$state' is not done or pending")
        when (kind) {
            "operation" -> {
                out += operationFindings(id, task, state, operations, code)
            }

            "channel" -> {
                if (id !in
                    channels
                ) {
                    out += Finding(NAME, id, "the table lists a channel the contract does not hold")
                }
            }

            else -> {
                out += Finding(NAME, id, "kind '$kind' is not operation or channel")
            }
        }
        return out
    }

    private fun operationFindings(
        id: String,
        task: String,
        state: String,
        operations: Set<String>,
        code: Code,
    ): List<Finding> {
        val out = ArrayList<Finding>()
        if (id !in operations) out += Finding(NAME, id, "the table lists an operationId the contract does not hold")
        val answers501 = id in code.stubbed || id !in code.implemented
        if (state == "done" &&
            answers501
        ) {
            out += Finding(NAME, id, "task $task is done but the operation still answers 501")
        }
        if (state == "pending" && !answers501) {
            out += Finding(NAME, id, "task $task is pending but the operation is implemented (stale row)")
        }
        return out
    }
}
