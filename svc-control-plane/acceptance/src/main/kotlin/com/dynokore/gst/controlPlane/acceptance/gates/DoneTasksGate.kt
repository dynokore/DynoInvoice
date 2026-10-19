package com.dynokore.gst.controlPlane.acceptance.gates

/**
 * The done-tasks gate (INV-T-4984, INV-Q-7424): `done-tasks.txt` at the repository root lists, one INV-T key per line,
 * the tasks that are done; the merge request of each task adds its own key. It fails when
 * - the file is missing;
 * - a line is not an `INV-T-<n>` key (a blank line, a comment, trailing text or a look-alike such as `INV-T-12x`);
 * - a listed key is listed twice or names no `operations.tsv` row;
 * - an `operations.tsv` row names a listed key while its operation still answers 501.
 *
 * Deny by default: anything the gate cannot classify is a finding.
 */
object DoneTasksGate {
    const val NAME = "done-tasks.txt"
    private val KEY = Regex("INV-T-[0-9]+")

    fun check(
        doneTasks: String?,
        table: String?,
        code: OperationsGate.Code,
    ): List<Finding> {
        if (doneTasks == null) return listOf(Finding(NAME, NAME, "the file is missing"))
        val lines = doneTasks.removeSuffix("\n").split("\n")
        val findings = ArrayList<Finding>()
        val keys = LinkedHashSet<String>()
        for (line in lines) {
            when {
                !KEY.matches(line) -> findings += Finding(NAME, line, "a line is exactly one INV-T-<n> key")
                !keys.add(line) -> findings += Finding(NAME, line, "listed twice")
            }
        }
        val rows =
            table
                ?.let(Tsv::rows)
                ?.second
                .orEmpty()
                .filter { it.size == 4 }
        for (key in keys) {
            if (rows.none { it[2] == key }) findings += Finding(NAME, key, "the key names no operations.tsv row")
        }
        for (row in rows.filter { it[2] in keys && it[0] == "operation" }) {
            if (row[1] in code.stubbed || row[1] !in code.implemented) {
                findings += Finding(NAME, row[1], "task ${row[2]} is listed done but the operation still answers 501")
            }
        }
        return findings
    }
}
