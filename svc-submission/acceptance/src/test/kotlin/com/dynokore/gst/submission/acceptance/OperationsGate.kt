package com.dynokore.gst.submission.acceptance

import org.yaml.snakeyaml.Yaml

/**
 * The contract gate (INV-T-5964): `operations.tsv` lists every operationId and channel of the submission contract
 * with the task that implements it, or exactly `UNASSIGNED` where no published task implements it yet.
 *
 * Deny by default: a contract item missing from the table, a table row naming no contract item, a duplicate row, a
 * task cell that is neither an `INV-T-<digits>` key nor exactly `UNASSIGNED` (so `unassigned` and `TBD` fail), and a
 * listed task that is done while its operation still answers 501.
 *
 * The done set is the file `done-tasks.txt` (INV-Q-7424 A), one INV-T key per line. The gate fails when the file is
 * missing, when a line is not exactly an INV-T key, when a listed key names no row of the table, and when a row names
 * a listed key while its operation still answers 501.
 */
object OperationsGate {
    private val TASK_KEY = Regex("INV-T-[0-9]+")
    private const val UNASSIGNED = "UNASSIGNED"
    private val HTTP_METHODS = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")

    data class Row(
        val kind: String,
        val id: String,
        val task: String,
    )

    fun contractOperations(openapi: String): Set<String> {
        val paths = (Yaml().load<Map<String, Any?>>(openapi)["paths"] as? Map<*, *>).orEmpty()
        return paths.values
            .filterIsInstance<Map<*, *>>()
            .flatMap { item ->
                item.entries
                    .filter { (it.key as? String)?.lowercase() in HTTP_METHODS }
                    .mapNotNull { ((it.value as? Map<*, *>)?.get("operationId")) as? String }
            }.toSet()
    }

    fun contractChannels(asyncapi: String): Set<String> {
        val channels = (Yaml().load<Map<String, Any?>>(asyncapi)["channels"] as? Map<*, *>).orEmpty()
        return channels.values.mapNotNull { (it as? Map<*, *>)?.get("address") as? String }.toSet()
    }

    fun parse(tsv: String): List<Row> {
        val lines = tsv.lines().filter { it.isNotBlank() }
        require(lines.isNotEmpty() && lines.first().split('\t') == listOf("kind", "id", "task")) {
            "operations.tsv: the first line must be the header kind<TAB>id<TAB>task"
        }
        return lines.drop(1).map { line ->
            val cells = line.split('\t')
            require(cells.size == 3) { "operations.tsv: a row has exactly three tab-separated cells: '$line'" }
            Row(cells[0], cells[1], cells[2])
        }
    }

    /** Parses `done-tasks.txt`; [text] is null when the file is missing. Returns the keys and the findings. */
    fun parseDone(text: String?): Pair<Set<String>, List<String>> {
        if (text == null) return emptySet<String>() to listOf("done-tasks.txt: the file is missing")
        val findings = mutableListOf<String>()
        val keys = mutableSetOf<String>()
        text.lines().filter { it.isNotEmpty() }.forEach { line ->
            if (TASK_KEY.matches(line)) {
                keys += line
            } else {
                findings += "done-tasks.txt: '$line' is not exactly an INV-T key"
            }
        }
        return keys to findings
    }

    private fun rowFindings(
        row: Row,
        operations: Set<String>,
        channels: Set<String>,
        stubs: Set<String>,
        doneTasks: Set<String>,
    ): List<String> {
        val findings = mutableListOf<String>()
        if (row.kind != "operation" && row.kind != "channel") findings += "unknown kind '${row.kind}' for '${row.id}'"
        if (row.task != UNASSIGNED && !TASK_KEY.matches(row.task)) {
            findings += "${row.kind} ${row.id}: task '${row.task}' is neither an INV-T key nor exactly $UNASSIGNED"
        }
        val known = if (row.kind == "channel") row.id in channels else row.kind == "operation" && row.id in operations
        if (!known) findings += "${row.kind} ${row.id}: not in the contract"
        if (row.kind == "operation" && row.task in doneTasks && row.id in stubs) {
            findings += "operation ${row.id}: task ${row.task} is done but the operation still answers 501"
        }
        return findings
    }

    /**
     * @param stubs the operationIds that still answer 501 (no override of the generated interface's default)
     * @param doneFile the text of `done-tasks.txt`, or null when the file is missing
     */
    fun findings(
        tsv: String,
        openapi: String,
        asyncapi: String,
        stubs: Set<String>,
        doneFile: String?,
    ): List<String> {
        val rows = parse(tsv)
        val (doneTasks, doneFindings) = parseDone(doneFile)
        val operations = contractOperations(openapi)
        val channels = contractChannels(asyncapi)
        val findings = doneFindings.toMutableList()
        (doneTasks - rows.map { it.task }.toSet())
            .forEach { findings += "done-tasks.txt: $it names no row of operations.tsv" }
        rows
            .groupBy { it.kind to it.id }
            .filter { it.value.size > 1 }
            .keys
            .forEach { findings += "duplicate row for ${it.first} ${it.second}" }
        rows.forEach { findings += rowFindings(it, operations, channels, stubs, doneTasks) }
        (operations - rows.filter { it.kind == "operation" }.map { it.id }.toSet())
            .forEach { findings += "operation $it: in the contract, missing from operations.tsv" }
        (channels - rows.filter { it.kind == "channel" }.map { it.id }.toSet())
            .forEach { findings += "channel $it: in the contract, missing from operations.tsv" }
        return findings
    }
}
