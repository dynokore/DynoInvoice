package com.dynokore.gst.submission.acceptance

import java.nio.file.Files
import java.nio.file.Path

/**
 * The queue-table guard (INV-T-7275, INV-Q-7266 A), fails closed: the service reaches the queue table only through
 * `WorkQueue`. The scan covers the main source sets of every Gradle module named in `settings.gradle.kts`: the
 * directories `<module>/src/main/kotlin`, `<module>/src/main/java` and `<module>/src/main/resources`, each built by
 * equality of its path, skipped when it does not exist. Tests, other source sets, build output and generated code are
 * never scanned. A scanned file holding `work_queue`, `queue_item` or `WORK_QUEUE`, or naming the generated jOOQ table
 * class `WorkQueue`, is a finding, unless it sits directly in the allowlisted migration directory (equality of the
 * path). An empty scan, or one without the allowlisted directory (a scanned directory or under one), is a finding.
 */
object QueueTableGuard {
    val SOURCE_SETS = listOf("src/main/kotlin", "src/main/java", "src/main/resources")
    const val MIGRATION_DIRECTORY = "submission-adapters/src/main/resources/db/migration"
    private val FORBIDDEN = listOf("work_queue", "queue_item", "WORK_QUEUE")
    private val JOOQ_TABLE = Regex("""\bjooq\.tables\.WorkQueue\b""")
    private val INCLUDE_ARGUMENT = Regex(""""([^"]+)"""")

    /** The module names of the `include(...)` calls of [settings] (comments after `//` are cut). */
    fun modules(settings: String): List<String> {
        val code = settings.lines().joinToString("\n") { it.substringBefore("//") }
        return Regex("""\binclude\s*\(([^)]*)\)""")
            .findAll(code)
            .flatMap { INCLUDE_ARGUMENT.findAll(it.groupValues[1]).map { argument -> argument.groupValues[1] } }
            .map { it.removePrefix(":") }
            .toList()
    }

    /** The scanned directories that exist, as paths relative to [root] with `/` separators. */
    fun scannedDirectories(
        root: Path,
        modules: List<String>,
    ): List<String> =
        modules.flatMap { module -> SOURCE_SETS.map { "$module/$it" } }.filter { Files.isDirectory(root.resolve(it)) }

    /** The findings over [root]; an empty list means the scan passed. */
    fun findings(
        root: Path,
        modules: List<String>,
        allowlistedDirectory: String = MIGRATION_DIRECTORY,
    ): List<String> {
        val directories = scannedDirectories(root, modules)
        val problems = mutableListOf<String>()
        val files = directories.flatMap { directory -> filesOf(root, directory) }
        if (files.isEmpty()) problems += "the scanned directory set holds no file"
        if (!holds(root, directories, allowlistedDirectory)) {
            problems +=
                "the allowlisted $allowlistedDirectory is not in the scan"
        }
        files.forEach { (relative, parent) ->
            if (parent == allowlistedDirectory) return@forEach
            val text = Files.readString(root.resolve(relative))
            FORBIDDEN.filter { it in text }.forEach { problems += "$relative contains $it" }
            if (JOOQ_TABLE.containsMatchIn(text)) problems += "$relative uses the jOOQ table WorkQueue"
        }
        return problems
    }

    /** True when the existing [directory] is a scanned directory or lies under one (segment equality). */
    private fun holds(
        root: Path,
        directories: List<String>,
        directory: String,
    ): Boolean =
        Files.isDirectory(root.resolve(directory)) &&
            directories.any { directory == it || directory.startsWith("$it/") }

    private fun filesOf(
        root: Path,
        directory: String,
    ): List<Pair<String, String>> =
        Files.walk(root.resolve(directory)).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .map { root.relativize(it).joinToString("/") to root.relativize(it.parent).joinToString("/") }
                .toList()
        }
}
