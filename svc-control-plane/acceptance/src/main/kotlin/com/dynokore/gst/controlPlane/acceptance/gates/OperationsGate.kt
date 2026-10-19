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
 * Columns: `kind` (operation|channel), `id` (operationId, or the AsyncAPI channel key), `task`, `state`
 * (done|pending). Deny by default: anything the gate cannot classify is a finding.
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
        return (root["channels"] as? Map<*, *>)?.keys?.filterIsInstance<String>()?.toSet() ?: emptySet()
    }

    /** The names of the functions the adapters' main sources override (an operation is implemented only if overridden). */
    fun overrides(adapterSources: List<File>): Set<String> =
        adapterSources
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> OVERRIDE.findAll(stripComments(file.readText())).map { it.groupValues[1] }.toList() }
            .toSet()

    /** The operationIds the adapters' main sources explicitly answer 501 for. */
    fun stubs(adapterSources: List<File>): Set<String> =
        adapterSources
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> STUB.findAll(stripComments(file.readText())).map { it.groupValues[1] }.toList() }
            .toSet()

    fun check(
        openapi: String,
        asyncapi: String,
        table: String?,
        implemented: Set<String>,
        stubbed: Set<String>,
    ): List<Finding> {
        val findings = ArrayList<Finding>()
        val parsed = table?.let(Tsv::rows)
        if (parsed == null) return listOf(Finding(NAME, NAME, "the table is missing or empty"))
        val (header, rows) = parsed
        if (header != HEADER) findings += Finding(NAME, "header", "expected columns $HEADER, found $header")
        val operations = contractOperations(openapi)
        val channels = contractChannels(asyncapi)
        val seen = HashSet<Pair<String, String>>()
        for (row in rows) {
            if (row.size != HEADER.size || row.any { it.isEmpty() }) {
                findings += Finding(NAME, row.joinToString(" "), "a row has the four columns $HEADER, all filled")
                continue
            }
            val (kind, id, task, state) = row
            if (!seen.add(kind to id)) findings += Finding(NAME, id, "listed twice")
            if (!TASK.matches(task)) findings += Finding(NAME, id, "task '$task' is not an INV-T-<n> key")
            if (state != "done" && state != "pending") findings += Finding(NAME, id, "state '$state' is not done or pending")
            when (kind) {
                "operation" -> {
                    if (id !in operations) findings += Finding(NAME, id, "the table lists an operationId the contract does not hold")
                    val answers501 = id in stubbed || id !in implemented
                    if (state == "done" && answers501) findings += Finding(NAME, id, "task $task is done but the operation still answers 501")
                    if (state == "pending" && !answers501) findings += Finding(NAME, id, "task $task is pending but the operation is implemented (stale row)")
                }
                "channel" -> if (id !in channels) findings += Finding(NAME, id, "the table lists a channel the contract does not hold")
                else -> findings += Finding(NAME, id, "kind '$kind' is not operation or channel")
            }
        }
        val listed = rows.filter { it.size == HEADER.size }.map { it[0] to it[1] }.toSet()
        operations.filter { "operation" to it !in listed }.sorted().forEach {
            findings += Finding(NAME, it, "the contract holds this operationId but the table does not list it")
        }
        channels.filter { "channel" to it !in listed }.sorted().forEach {
            findings += Finding(NAME, it, "the contract holds this channel but the table does not list it")
        }
        stubbed.filter { it !in operations }.sorted().forEach {
            findings += Finding(NAME, it, "a 501 stub names an operationId the contract does not hold")
        }
        return findings
    }

    internal fun stripComments(source: String): String = SourceText.stripComments(source)
}

internal object SourceText {
    /** Removes line comments and (nested) block comments, keeping string literals intact. */
    fun stripComments(source: String): String {
        val out = StringBuilder()
        var i = 0
        var depth = 0
        var inString = false
        var inRaw = false
        while (i < source.length) {
            val c = source[i]
            val two = if (i + 1 < source.length) source.substring(i, i + 2) else ""
            when {
                depth > 0 && two == "/*" -> { depth++; i += 2 }
                depth > 0 && two == "*/" -> { depth--; i += 2 }
                depth > 0 -> i++
                inRaw -> { if (source.startsWith("\"\"\"", i)) { inRaw = false; out.append("\"\"\""); i += 3 } else out.append(c).also { i++ } }
                inString -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < source.length) { out.append(source[i + 1]); i += 2 } else { if (c == '"') inString = false; i++ }
                }
                source.startsWith("\"\"\"", i) -> { inRaw = true; out.append("\"\"\""); i += 3 }
                c == '"' -> { inString = true; out.append(c); i++ }
                two == "//" -> { while (i < source.length && source[i] != '\n') i++ }
                two == "/*" -> { depth = 1; i += 2 }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}

/** Source text with comments and string literals removed, for rules that look for code only. */
fun stripForRule(source: String): String = SourceText.stripComments(source).replace(Regex("\"(?:[^\"\\\\\\n]|\\\\.)*\""), "\"\"")
