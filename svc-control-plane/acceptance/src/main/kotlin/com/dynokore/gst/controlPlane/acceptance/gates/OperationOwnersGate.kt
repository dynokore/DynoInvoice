package com.dynokore.gst.controlPlane.acceptance.gates

/**
 * The owner pin gate (INV-T-12857, INV-Q-12861): `operation-owners.tsv` at the repository root pins the task cell of
 * every `operations.tsv` row by kind and id. `OperationsGate` only tests the shape of a task cell, so a slip such as a
 * trailing digit passes there; this gate compares the two files by exact string equality (no trimming, no case
 * folding). The `state` column is not pinned. It fails when
 * - the pin file or the table is missing or empty;
 * - the pin header is not `kind, id, task`, or a pin row lacks the three filled columns;
 * - a pair kind and id is pinned twice, a table row has no pin, or a pin has no table row;
 * - a pair has different task cells in the two files.
 *
 * Deny by default: anything the gate cannot classify is a finding.
 */
object OperationOwnersGate {
    const val NAME = "operation-owners.tsv"
    private val HEADER = listOf("kind", "id", "task")

    fun check(
        owners: String?,
        table: String?,
    ): List<Finding> {
        if (owners.isNullOrBlank()) return listOf(Finding(NAME, NAME, "the pin file is missing or empty"))
        if (table.isNullOrBlank()) return listOf(Finding(NAME, NAME, "the table is missing or empty"))
        val pinLines = owners.removeSuffix("\n").split("\n").map { it.split('\t') }
        val findings = ArrayList<Finding>()
        val header = pinLines.first()
        if (header != HEADER) findings += Finding(NAME, "header", "expected columns $HEADER, found $header")
        val pins = LinkedHashMap<Pair<String, String>, String>()
        for (row in pinLines.drop(1)) {
            if (row.size != HEADER.size || row.any { it.isEmpty() }) {
                findings += Finding(NAME, row.joinToString(" "), "a pin row has the three columns $HEADER, all filled")
            } else if (pins.putIfAbsent(row[0] to row[1], row[2]) != null) {
                findings += Finding(NAME, row[1], "listed twice")
            }
        }
        val rows =
            table
                .lines()
                .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
                .drop(1)
                .map { it.split('\t') }
                .filter { it.size == 4 && it.none(String::isEmpty) }
        val tableTasks = LinkedHashMap<Pair<String, String>, String>()
        for (row in rows) {
            val pair = row[0] to row[1]
            val pinned = pins[pair]
            when {
                pinned == null -> {
                    findings += Finding(NAME, row[1], "the table row has no pin")
                }

                pinned != row[2] -> {
                    findings += Finding(NAME, row[1], "task ${row[2]} in operations.tsv differs from $pinned in $NAME")
                }
            }
            tableTasks.putIfAbsent(pair, row[2])
        }
        for (pair in pins.keys) {
            if (pair !in tableTasks) findings += Finding(NAME, pair.second, "the pin has no table row")
        }
        return findings
    }
}
