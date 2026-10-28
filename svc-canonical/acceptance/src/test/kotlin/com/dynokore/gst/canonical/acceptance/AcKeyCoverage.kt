package com.dynokore.gst.canonical.acceptance

/**
 * The acceptance-criterion key gate (INV-T-5544), fails closed. `ac-keys.tsv` has one row per acceptance criterion
 * the tasks of this service carry: `criterion`, `task`, `wave` (a number; 3 for a criterion this service's tasks run,
 * 4 for a deferred one).
 *
 * - the table is missing, empty or malformed: a finding;
 * - a row whose wave is at most the run wave and whose key is not a whole token of the name of at least one executed
 *   test: a finding naming the row;
 * - a test name that holds a key in no row, or a look-alike of a key (`INV-AC-21801`, `INV-AC-2180x`,
 *   `xINV-AC-2180`): a finding naming the test.
 */
object AcKeyCoverage {
    const val RUN_WAVE = 3
    private val KEY = Regex("INV-AC-[0-9]+")
    private val TASK = Regex("INV-T-[0-9]+")
    private val CANDIDATE = Regex("INV-AC-[0-9][0-9A-Za-z_]*")
    private val WORD = Regex("[A-Za-z0-9_]")
    private val HEADER = listOf("criterion", "task", "wave")

    data class Row(
        val criterion: String,
        val task: String,
        val wave: Int,
    )

    /** The keys of [name] that are whole tokens, and the look-alikes that are not. */
    fun keysIn(name: String): Pair<Set<String>, Set<String>> {
        val whole = mutableSetOf<String>()
        val lookAlikes = mutableSetOf<String>()
        CANDIDATE.findAll(name).forEach { match ->
            val before = name.getOrNull(match.range.first - 1)?.toString()
            val glued = before != null && WORD.matches(before)
            if (!glued && KEY.matches(match.value)) whole += match.value else lookAlikes += match.value
        }
        return whole to lookAlikes
    }

    private fun parseRow(line: String): Row? {
        val cells = line.split('\t')
        val wave = cells.getOrNull(2)?.toIntOrNull()
        val valid = cells.size == HEADER.size && KEY.matches(cells[0]) && TASK.matches(cells[1])
        return if (valid && wave != null) Row(cells[0], cells[1], wave) else null
    }

    /** The rows of the table and the problems that make it unusable. */
    fun parse(table: String?): Pair<List<Row>, List<String>> {
        val lines = table?.lines()?.filter { it.isNotBlank() }.orEmpty()
        val problem =
            when {
                table == null -> "ac-keys.tsv is missing"

                lines.firstOrNull()?.split(
                    '\t',
                ) != HEADER -> "ac-keys.tsv: the first line must be the header criterion, task, wave"

                lines.size == 1 -> "ac-keys.tsv has no rows"

                else -> null
            }
        if (problem != null) return emptyList<Row>() to listOf(problem)
        val parsed = lines.drop(1).map { it to parseRow(it) }
        val problems =
            parsed
                .filter { it.second == null }
                .map { "ac-keys.tsv: malformed row '${it.first}'" }
                .toMutableList()
        val rows = parsed.mapNotNull { it.second }
        rows.groupBy { it.criterion }.filter { it.value.size > 1 }.keys.forEach {
            problems +=
                "ac-keys.tsv: duplicate row for $it"
        }
        return rows to problems
    }

    fun findings(
        table: String?,
        executedTestNames: List<String>,
    ): List<String> {
        val (rows, problems) = parse(table)
        val findings = problems.toMutableList()
        if (rows.isEmpty()) return findings
        val covered = executedTestNames.flatMap { keysIn(it).first }.toSet()
        rows.filter { it.wave <= RUN_WAVE && it.criterion !in covered }.forEach {
            findings +=
                "${it.criterion} (task ${it.task}, wave ${it.wave}): no executed test name holds the key as a whole token"
        }
        val known = rows.map { it.criterion }.toSet()
        executedTestNames.forEach { name ->
            val (whole, lookAlikes) = keysIn(name)
            (whole - known).forEach { findings += "test '$name' holds $it, which is in no row of ac-keys.tsv" }
            lookAlikes.forEach { findings += "test '$name' holds the look-alike '$it', which is not a whole key token" }
        }
        return findings
    }
}
