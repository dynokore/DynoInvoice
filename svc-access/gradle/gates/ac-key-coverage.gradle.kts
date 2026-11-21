// gradle/gates/ac-key-coverage.gradle.kts — the acKeyCoverage check (INV-T-4934, coordinator rulings INV-Q-4876, INV-Q-4896).
//
// Fails closed. `ac-keys.tsv` at the repository root lists, one row per acceptance criterion the tasks of this service
// carry: criterion, task key, wave (2 for a criterion run by this service's tasks, 3 or 4 for a deferred one) and an
// optional module (INV-T-26530): the module directory that owns the proof. A row has three cells (not bound) or four
// (bound). The check fails when
//   - the table is missing, empty (no data row) or malformed (the header is not criterion, task, wave, module; a bad
//     criterion, task or wave; a repeated criterion; a module cell that is not, by whole-string equality, a directory at
//     the repository root holding a src/test tree);
//   - a row with wave 2 has no test whose name contains the criterion key as a whole token (for a bound row: in its module);
//   - a test of another module carries the key of a bound row (the module of a test is the first path segment of its file);
//   - a test name holds a key that is in no row, or an INV-AC-<digits> that is not a whole token.
// A test is a Kotlin function in a `src/test` tree of any module whose name is written in backticks or plain; one marked
// @Disabled does not count. A key is a whole token when no letter, digit, underscore or hyphen touches it.

abstract class AcKeyCoverage : DefaultTask() {
    @get:InputFile
    @get:Optional
    abstract val table: RegularFileProperty

    @get:InputFiles
    abstract val testSources: ConfigurableFileCollection

    @get:Internal
    abstract val root: DirectoryProperty

    @get:Input
    abstract val tablePath: Property<String>

    @TaskAction
    fun check() {
        val findings = mutableListOf<String>()
        val file = table.orNull?.asFile?.takeIf { it.isFile }
        if (file == null) throw GradleException("acKeyCoverage: ${tablePath.get()} is missing")
        val rootDir = root.get().asFile
        val rows = parse(file.readLines(), rootDir, findings)
        if (rows.isEmpty() && findings.isEmpty()) findings += "${tablePath.get()} has no data row"
        val names = testSources.files.filter { it.extension == "kt" }.sortedBy { it.path }.flatMap { testNames(it, rootDir) }
        val covered = mutableSetOf<String>()
        names.forEach { (where, name) ->
            KEY.findAll(name).forEach { match ->
                val key = match.value
                if (!wholeToken(name, match.range)) {
                    findings += "test '$name' ($where) holds '$key' that is not a whole token"
                } else if (key !in rows.keys) {
                    findings += "test '$name' ($where) holds $key, which is in no row of ${tablePath.get()}"
                } else {
                    val row = rows.getValue(key)
                    val module = where.substringBefore('/')
                    if (row.module != null && module != row.module) {
                        findings += "test '$name' ($where) carries $key in module $module, but the row of $key names module ${row.module}"
                    } else {
                        covered += key
                    }
                }
            }
        }
        rows.filter { it.value.wave == 2 && it.key !in covered }.forEach { (key, row) ->
            findings += "row $key (task ${row.task}, wave 2) has no test whose name contains $key as a whole token"
        }
        if (findings.isNotEmpty()) {
            throw GradleException("acKeyCoverage failed:\n  " + findings.distinct().joinToString("\n  "))
        }
    }

    private class Row(val task: String, val wave: Int, val module: String?)

    private fun parse(
        lines: List<String>,
        rootDir: File,
        findings: MutableList<String>,
    ): Map<String, Row> {
        val rows = linkedMapOf<String, Row>()
        val data = lines.map { it.trimEnd(' ', '\r') }.filter { it.isNotBlank() && !it.startsWith("#") }
        if (data.isEmpty()) return rows
        if (data.first().split('\t') != listOf("criterion", "task", "wave", "module")) {
            findings += "header must be 'criterion<TAB>task<TAB>wave<TAB>module', was '${data.first()}'"
        }
        data.drop(1).forEachIndexed { index, line ->
            val cells = line.split('\t')
            val label = "row ${index + 1} '$line'"
            if (cells.size != 3 && cells.size != 4) {
                findings += "$label must have three or four tab-separated columns"
                return@forEachIndexed
            }
            val (criterion, task, wave) = cells
            val module = cells.getOrNull(3)
            if (module != null && module !in moduleDirectories(rootDir)) {
                findings += "$label: module '$module' must name a directory at the repository root that holds a src/test tree"
            }
            if (!Regex("INV-AC-[0-9]+").matches(criterion)) findings += "$label: criterion must be INV-AC-<digits>"
            if (!Regex("INV-T-[0-9]+").matches(task)) findings += "$label: task must be INV-T-<digits>"
            val waveNumber = wave.toIntOrNull()
            if (waveNumber == null || waveNumber !in 2..4) findings += "$label: wave must be 2, 3 or 4"
            if (criterion in rows) findings += "$label: $criterion is listed twice"
            if (waveNumber != null) rows.putIfAbsent(criterion, Row(task, waveNumber, module))
        }
        return rows
    }

    /** Names of the directories at the repository root that hold a `src/test*` directory (compared by equality). */
    private fun moduleDirectories(rootDir: File): Set<String> =
        rootDir.listFiles { f -> f.isDirectory }.orEmpty()
            .filter { d -> File(d, "src").listFiles { t -> t.isDirectory && t.name.startsWith("test") }.orEmpty().isNotEmpty() }
            .map { it.name }.toSet()

    private fun wholeToken(
        name: String,
        range: IntRange,
    ): Boolean {
        val before = if (range.first == 0) null else name[range.first - 1]
        val after = if (range.last + 1 >= name.length) null else name[range.last + 1]
        fun glued(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_' || c == '-')
        return !glued(before) && !glued(after)
    }

    /** (path:line, name) of every test function of [file] that is not @Disabled; the path is relative to [rootDir]. */
    private fun testNames(
        file: File,
        rootDir: File,
    ): List<Pair<String, String>> {
        val lines = file.readLines()
        val result = mutableListOf<Pair<String, String>>()
        var inBlockComment = false
        lines.forEachIndexed { index, raw ->
            var line = raw
            if (inBlockComment) {
                val end = line.indexOf("*/")
                if (end < 0) return@forEachIndexed
                inBlockComment = false
                line = line.substring(end + 2)
            }
            while (line.contains("/*")) {
                val start = line.indexOf("/*")
                val end = line.indexOf("*/", start + 2)
                if (end < 0) {
                    inBlockComment = true
                    line = line.substring(0, start)
                } else {
                    line = line.substring(0, start) + " " + line.substring(end + 2)
                }
            }
            val code = line.substringBefore("//").takeIf { !it.trimStart().startsWith("*") } ?: return@forEachIndexed
            FUN.findAll(code).forEach { match ->
                val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
                val disabled = (maxOf(0, index - 3)..index).any { lines[it].contains("@Disabled") }
                if (!disabled) result += "${file.relativeTo(rootDir).invariantSeparatorsPath}:${index + 1}" to name
            }
        }
        return result
    }

    private companion object {
        val KEY = Regex("INV-AC-[0-9]+")
        val FUN = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:`([^`]*)`|([A-Za-z_][A-Za-z0-9_]*))\s*\(""")
    }
}

val acKeyCoverage =
    tasks.register<AcKeyCoverage>("acKeyCoverage") {
        group = "verification"
        description = "INV-T-4934: every wave-2 criterion of ac-keys.tsv has a test named by its key; no test names an unlisted key."
        tablePath.set("ac-keys.tsv")
        root.set(layout.projectDirectory)
        table.set(layout.projectDirectory.file("ac-keys.tsv"))
        testSources.from(
            fileTree(layout.projectDirectory) {
                include("*/src/test*/**/*.kt")
                exclude("**/build/**")
            },
        )
    }
tasks.named("check") { dependsOn(acKeyCoverage) }
