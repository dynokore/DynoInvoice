package com.dynokore.gst.access.bootstrap

import org.yaml.snakeyaml.Yaml

/**
 * The contract gate (INV-T-4934): `operations.tsv` lists every operationId and channel of the contract with the task
 * that
 * implements it. It fails closed, on any of:
 *  - an operationId or channel of the contract missing from the table, or a table row the contract does not hold;
 *  - a row twice, a bad header, kind, task or state, an empty table;
 *  - a row `done` whose task is `UNASSIGNED`, or whose operation answers 501 (the generated interface's stub answer);
 *  - a channel `done` that the code does not publish, or published by the code but not `done`.
 * A task is `INV-T-<digits>` or exactly `UNASSIGNED`; a state is exactly `open` or `done`.
 */
object OperationsGate {
    const val UNASSIGNED = "UNASSIGNED"

    data class Row(
        val kind: String,
        val name: String,
        val task: String,
        val state: String,
    )

    data class Contract(
        val operations: Map<String, OperationRef>,
        val channels: Set<String>,
    )

    data class OperationRef(
        val method: String,
        val path: String,
        val raw: Map<String, Any?>,
    )

    @Suppress("UNCHECKED_CAST")
    fun contractOf(
        openApi: String,
        asyncApi: String,
    ): Contract {
        val paths = Yaml().load<Map<String, Any?>>(openApi)["paths"] as Map<String, Map<String, Any?>>
        val operations = linkedMapOf<String, OperationRef>()
        paths.forEach { (path, item) ->
            item.filterKeys { it in METHODS }.forEach { (method, operation) ->
                val raw = operation as Map<String, Any?>
                val id = raw["operationId"] as? String
                if (id != null) operations[id] = OperationRef(method.uppercase(), path, raw)
            }
        }
        val channels = (Yaml().load<Map<String, Any?>>(asyncApi)["channels"] as Map<String, Any?>).keys
        return Contract(operations, channels)
    }

    fun parse(
        lines: List<String>,
        findings: MutableList<String>,
    ): List<Row> {
        val data = lines.map { it.trimEnd() }.filter { it.isNotBlank() && !it.startsWith("#") }
        if (data.isEmpty()) {
            findings += "operations.tsv has no row"
            return emptyList()
        }
        if (data.first().split('\t') != listOf("kind", "name", "task", "state")) {
            findings += "header must be 'kind<TAB>name<TAB>task<TAB>state', was '${data.first()}'"
        }
        return data.drop(1).mapNotNull { line ->
            val cells = line.split('\t')
            if (cells.size != 4) {
                findings += "row '$line' must have four tab-separated columns"
                return@mapNotNull null
            }
            Row(cells[0], cells[1], cells[2], cells[3])
        }
    }

    /** Every finding, empty when the gate passes. [answers] gives the HTTP status an operation answers with. */
    fun verify(
        rows: List<Row>,
        contract: Contract,
        parseFindings: List<String> = emptyList(),
        published: Set<String>,
        answers: (OperationRef) -> Int,
    ): List<String> {
        val findings = parseFindings.toMutableList()
        if (rows.isEmpty() && findings.isEmpty()) findings += "operations.tsv has no row"
        rows.groupBy { it.kind to it.name }.filterValues { it.size > 1 }.keys.forEach {
            findings += "${it.first} ${it.second} is listed twice"
        }
        rows.forEach { row -> findings += rowFindings(row, contract, published, answers) }
        val listed = rows.map { it.kind to it.name }.toSet()
        contract.operations.keys.filter { ("operation" to it) !in listed }.forEach {
            findings += "operation $it is in the contract and missing from operations.tsv"
        }
        contract.channels.filter { ("channel" to it) !in listed }.forEach {
            findings += "channel $it is in the contract and missing from operations.tsv"
        }
        return findings.distinct()
    }

    private fun rowFindings(
        row: Row,
        contract: Contract,
        published: Set<String>,
        answers: (OperationRef) -> Int,
    ): List<String> {
        val label = "${row.kind} ${row.name}"
        val findings = mutableListOf<String>()
        if (row.kind !in setOf("operation", "channel")) findings += "$label: kind must be operation or channel"
        if (row.task != UNASSIGNED && !TASK.matches(row.task)) {
            findings += "$label: task must be INV-T-<digits> or UNASSIGNED"
        }
        if (row.state !in setOf("open", "done")) findings += "$label: state must be open or done"
        if (row.state == "done" && row.task == UNASSIGNED) findings += "$label: done but no task implements it"
        when (row.kind) {
            "operation" -> findings += operationFindings(row, label, contract, answers)
            "channel" -> findings += channelFindings(row, label, contract, published)
        }
        return findings
    }

    private fun operationFindings(
        row: Row,
        label: String,
        contract: Contract,
        answers: (OperationRef) -> Int,
    ): List<String> {
        val operation = contract.operations[row.name]
        return when {
            operation == null -> {
                listOf("$label: the contract holds no such operationId")
            }

            row.state == "done" && answers(operation) == STUB_STATUS -> {
                listOf("$label: marked done by ${row.task} but still answers $STUB_STATUS")
            }

            else -> {
                emptyList()
            }
        }
    }

    private fun channelFindings(
        row: Row,
        label: String,
        contract: Contract,
        published: Set<String>,
    ): List<String> {
        val findings = mutableListOf<String>()
        if (row.name !in contract.channels) findings += "$label: the contract holds no such channel"
        if (row.state == "done" && row.name !in published) {
            findings += "$label: marked done but the code does not publish it"
        }
        if (row.state != "done" &&
            row.name in published
        ) {
            findings += "$label: published by the code but not marked done"
        }
        return findings
    }

    private val TASK = Regex("INV-T-[0-9]+")
    private val METHODS = setOf("get", "put", "post", "delete", "patch")
    const val STUB_STATUS = 501
}
