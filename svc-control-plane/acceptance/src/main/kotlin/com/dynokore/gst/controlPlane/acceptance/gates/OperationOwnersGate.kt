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
    ): List<Finding> =
        when {
            owners.isNullOrBlank() -> listOf(Finding(NAME, NAME, "the pin file is missing or empty"))
            table.isNullOrBlank() -> listOf(Finding(NAME, NAME, "the table is missing or empty"))
            else -> compare(owners, table)
        }

    private fun compare(
        owners: String,
        table: String,
    ): List<Finding> {
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
        findings += rows.mapNotNull { rowFinding(it, pins[it[0] to it[1]]) }
        val listed = rows.map { it[0] to it[1] }.toSet()
        findings += pins.keys.filter { it !in listed }.map { Finding(NAME, it.second, "the pin has no table row") }
        return findings
    }

    private fun rowFinding(
        row: List<String>,
        pinned: String?,
    ): Finding? =
        when {
            pinned == null -> Finding(NAME, row[1], "the table row has no pin")
            pinned != row[2] -> Finding(NAME, row[1], "task ${row[2]} in operations.tsv differs from $pinned in $NAME")
            else -> null
        }
}
