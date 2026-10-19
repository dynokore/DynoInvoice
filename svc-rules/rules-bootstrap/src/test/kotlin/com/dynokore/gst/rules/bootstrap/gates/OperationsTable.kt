package com.dynokore.gst.rules.bootstrap.gates

import org.yaml.snakeyaml.Yaml

/**
 * The contract gate (INV-T-4965): `operations.tsv` lists every operationId of the rules OpenAPI document and every
 * channel (by address) of its AsyncAPI document with the task that implements it and whether that task is done. The
 * gate
 * fails when
 *  - the contract holds an operationId or channel that is missing from the table, or the table names one the contract
 *    does not hold (a typo or a stale row), or lists one twice;
 *  - a row is malformed (kind, task key, `task_done`);
 *  - a row says its task is done and the operation still answers 501 (no override in rules-adapters, or an override
 *    whose body is a stub or calls nothing), or its channel is not catalogued for emission.
 *
 * Known limit, stated because the gate reads source text and not a running service: an override counts as implemented
 * when its body is free of stub markers and calls a member of a collaborator; the 501 itself is the generated
 * interface's default body, so an operation without an override answers 501 by construction.
 */
internal object OperationsTable {
    data class Row(
        val kind: String,
        val id: String,
        val task: String,
        val done: Boolean,
    )

    const val HEADER = "kind\tid\ttask\ttask_done"
    private val TASK = Regex("""INV-T-\d+""")
    private val STUB_MARKERS =
        Regex("""NOT_IMPLEMENTED|\b501\b|\bTODO\b|NotImplemented|UnsupportedOperation|valueOf\(""")
    private val OVERRIDE = Regex("""\boverride\s+fun\s+(\w+)\s*\(""")

    @Suppress("UNCHECKED_CAST")
    fun contractOperations(openApi: String): Set<String> {
        val paths = (Yaml().load<Map<String, Any?>>(openApi)["paths"] as Map<String, Map<String, Any?>>)
        return paths.values
            .flatMap { path -> path.values.filterIsInstance<Map<String, Any?>>() }
            .mapNotNull { it["operationId"] as String? }
            .toSet()
    }

    @Suppress("UNCHECKED_CAST")
    fun contractChannels(asyncApi: String): Set<String> =
        (Yaml().load<Map<String, Any?>>(asyncApi)["channels"] as Map<String, Map<String, Any?>>)
            .values
            .map { it["address"] as String }
            .toSet()

    /** The override of each operation in the adapters' code and whether its body is an implementation. */
    fun overrides(adapters: Map<String, String>): Map<String, Boolean> =
        adapters.values
            .flatMap { text ->
                val code = KotlinSource.withoutComments(text)
                OVERRIDE
                    .findAll(code)
                    .map { match ->
                        val open = match.range.last
                        val close = matching(code, open)
                        val after = code.substring(close + 1)
                        val end = Regex("""\n\s*(override\s+fun|\}\s*$)""").find(after)?.range?.first ?: after.length
                        val body = after.substring(0, end)
                        match.groupValues[1] to
                            (!STUB_MARKERS.containsMatchIn(body) && Regex("""\w\.\w+\(""").containsMatchIn(body))
                    }.toList()
            }.toMap()

    private fun matching(
        code: String,
        open: Int,
    ): Int {
        var depth = 0
        for (i in open until code.length) {
            if (code[i] == '(') depth++
            if (code[i] == ')' && --depth == 0) return i
        }
        return code.length - 1
    }

    fun check(
        table: String?,
        openApi: String,
        asyncApi: String,
        adapters: Map<String, String>,
    ): List<String> {
        val findings = mutableListOf<String>()
        val rows = parse(table, findings)
        if (table == null || (findings.isNotEmpty() && rows.isEmpty())) return findings
        val contract = Contract(contractOperations(openApi), contractChannels(asyncApi))
        findings +=
            rows
                .groupBy { it.kind to it.id }
                .filterValues { it.size > 1 }
                .keys
                .map { "operations.tsv lists ${it.first} ${it.second} more than once" }
        findings += missing("operation", "operationId", contract.operations, rows)
        findings += missing("channel", "channel", contract.channels, rows)
        val code = Code(adapters.values.joinToString("\n") { KotlinSource.withoutComments(it) }, overrides(adapters))
        rows.forEach { findings += rowFindings(it, contract, code) }
        return findings
    }

    private class Contract(
        val operations: Set<String>,
        val channels: Set<String>,
    )

    private class Code(
        val text: String,
        val implemented: Map<String, Boolean>,
    )

    private fun parse(
        table: String?,
        findings: MutableList<String>,
    ): List<Row> {
        val lines = table?.lines()?.filter { it.isNotBlank() }.orEmpty()
        when {
            table == null -> findings += "operations.tsv is missing"
            lines.firstOrNull() != HEADER -> findings += "operations.tsv must start with the header '$HEADER'"
        }
        if (findings.isNotEmpty()) return emptyList()
        val rows = mutableListOf<Row>()
        lines.drop(1).forEachIndexed { index, line ->
            val cells = line.split('\t')
            val problem = problemOf(cells)
            if (problem == null) {
                rows += Row(cells[0], cells[1], cells[2], cells[3] == "yes")
            } else {
                findings += "operations.tsv line ${index + 2}: $problem"
            }
        }
        return rows
    }

    private fun problemOf(cells: List<String>): String? =
        when {
            cells.size != 4 -> "expected 4 tab-separated columns"
            cells[0] !in setOf("operation", "channel") -> "kind '${cells[0]}' is not operation or channel"
            cells[3] !in setOf("yes", "no") -> "(${cells[1]}) task_done '${cells[3]}' is not yes or no"
            cells[2] != "none" && !TASK.matches(cells[2]) -> "(${cells[1]}) '${cells[2]}' is not a task key"
            else -> null
        }

    private fun missing(
        kind: String,
        label: String,
        inContract: Set<String>,
        rows: List<Row>,
    ): List<String> =
        (inContract - rows.filter { it.kind == kind }.map { it.id }.toSet())
            .sorted()
            .map { "$label $it is in the contract and missing from operations.tsv" }

    private fun rowFindings(
        row: Row,
        contract: Contract,
        code: Code,
    ): List<String> {
        val where = "operations.tsv ${row.kind} ${row.id}"
        val known = if (row.kind == "operation") row.id in contract.operations else row.id in contract.channels
        val catalogued = "\"${row.id}\"" in code.text
        val unpublishedChannel = row.kind == "channel" && !row.done && !catalogued
        return listOfNotNull(
            "operations.tsv names ${row.kind} ${row.id}, which the contract does not hold".takeIf { !known },
            "$where: task 'none' is only for a channel nothing publishes"
                .takeIf { row.task == "none" && !unpublishedChannel },
            "operations.tsv marks ${row.task} done but operation ${row.id} still answers 501"
                .takeIf { row.done && row.kind == "operation" && code.implemented[row.id] != true },
            (
                "operations.tsv marks ${row.task} done but channel ${row.id} is not catalogued for " +
                    "emission in rules-adapters"
            ).takeIf { row.done && row.kind == "channel" && !catalogued },
        )
    }
}
