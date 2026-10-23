package com.dynokore.gst.aggregation.acceptance

import org.yaml.snakeyaml.Yaml

/**
 * The contract gate (INV-T-8451): `operations.tsv` lists every operationId and channel (its name, the key under
 * `channels:`) of the aggregation contract with the task that implements it and the state `open` or `done`.
 *
 * Deny by default: a contract item missing from the table, a table row naming no contract item, a duplicate row, a
 * task cell that is not an `INV-T-<digits>` key, a state other than `open` or `done`, and a listed task that is done
 * while its operation still answers 501. The one exception is the owner value `PLACEHOLDER` (INV-Q-8791 A): it is
 * accepted on exactly the row `channel aggregationExampleHappened` with the state `open`, compared by equality, and
 * the value `UNASSIGNED` is accepted on no row.
 *
 * The done set is the file `done-tasks.txt` (INV-Q-7424 A), one INV-T key per line. The gate fails when the file is
 * missing, when a line is not exactly an INV-T key, when a listed key names no row of the table, and when a row names
 * a listed key while its operation still answers 501.
 */
object OperationsGate {
    private val TASK_KEY = Regex("INV-T-[0-9]+")
    private const val PLACEHOLDER = "PLACEHOLDER"
    private const val PLACEHOLDER_KIND = "channel"
    private const val PLACEHOLDER_NAME = "aggregationExampleHappened"
    private val STATES = setOf("open", "done")
    private val HTTP_METHODS = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")

    data class Row(
        val kind: String,
        val id: String,
        val task: String,
        val state: String,
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
        return channels.keys.filterIsInstance<String>().toSet()
    }

    fun parse(tsv: String): List<Row> {
        val lines = tsv.lines().filter { it.isNotBlank() }
        require(lines.isNotEmpty() && lines.first().split('\t') == listOf("kind", "id", "task", "state")) {
            "operations.tsv: the first line must be the header kind<TAB>id<TAB>task<TAB>state"
        }
        return lines.drop(1).map { line ->
            val cells = line.split('\t')
            require(cells.size == 4) { "operations.tsv: a row has exactly four tab-separated cells: '$line'" }
            Row(cells[0], cells[1], cells[2], cells[3])
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

    private fun taskFindings(row: Row): List<String> {
        val label = "${row.kind} ${row.id}"
        val placeholderRow = row.kind == PLACEHOLDER_KIND && row.id == PLACEHOLDER_NAME
        return listOfNotNull(
            "$label: state '${row.state}' is neither open nor done".takeIf { row.state !in STATES },
            when {
                row.task == PLACEHOLDER && !placeholderRow -> {
                    "$label: $PLACEHOLDER is accepted only on $PLACEHOLDER_KIND $PLACEHOLDER_NAME"
                }

                row.task == PLACEHOLDER && row.state != "open" -> {
                    "$label: $PLACEHOLDER needs the state open, found '${row.state}'"
                }

                row.task != PLACEHOLDER && placeholderRow -> {
                    "$label: the task must be exactly $PLACEHOLDER"
                }

                row.task != PLACEHOLDER && !TASK_KEY.matches(row.task) -> {
                    "$label: task '${row.task}' is not an INV-T key"
                }

                else -> {
                    null
                }
            },
        )
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
        findings += taskFindings(row)
        val known = if (row.kind == "channel") row.id in channels else row.kind == "operation" && row.id in operations
        if (!known) findings += "${row.kind} ${row.id}: not in the contract"
        if (row.kind == "operation" && row.task in doneTasks && row.id in stubs) {
            findings += "operation ${row.id}: task ${row.task} is done but the operation still answers 501"
        }
        return findings
    }

    /**
     * The rows the split of INV-T-4979 sets (coordinator ruling INV-Q-8449): the task of each, compared by equality of
     * the whole INV-T key. A row of the table naming another key, or a look-alike such as `INV-T-49790`, is a finding
     * naming the row. A later slice that owns another row adds it here with its merge request.
     */
    val ASSIGNED: Map<Pair<String, String>, String> =
        mapOf(
            ("operation" to "intakeAggregationBatch") to "INV-T-8473",
            ("operation" to "getAggregateDrillDown") to "INV-T-4980",
            ("channel" to "aggregationAggregateReady") to "INV-T-4979",
            ("channel" to "aggregationAggregateWindowChanged") to "INV-T-8453",
        )

    fun assignmentFindings(tsv: String): List<String> {
        val rows = parse(tsv).associateBy { it.kind to it.id }
        return ASSIGNED.mapNotNull { (item, task) ->
            val row = rows[item]
            when {
                row == null -> "${item.first} ${item.second}: no row, the split sets $task"
                row.task != task -> "${row.kind} ${row.id}: task '${row.task}', the split sets $task"
                else -> null
            }
        }
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
