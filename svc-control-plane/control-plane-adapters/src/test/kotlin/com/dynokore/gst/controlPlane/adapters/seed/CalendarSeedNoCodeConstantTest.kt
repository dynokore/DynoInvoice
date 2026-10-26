package com.dynokore.gst.controlPlane.adapters.seed

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

/**
 * The scanner of the no-code-constant gate (INV-REQ-1350; owner answers INV-Q-10904 and INV-Q-10906): after comments
 * are removed, a statement that names the safety margin, the alert thresholds, the filing frequency or the windows and
 * also holds an integer, a collection constructor or one of the frequency and unit names is a finding.
 */
object CalendarNoCodeScan {
    /** The five module directories of the gate, compared by equality. */
    val MODULES =
        listOf(
            "control-plane-adapters",
            "control-plane-application",
            "control-plane-bootstrap",
            "control-plane-contract",
            "control-plane-domain",
        )

    private val NAMES = listOf("safetymargin", "alertthreshold", "alertthresholds", "filingfrequency", "windows")
    private val LITERALS = setOf("QUARTERLY", "MONTHLY", "DAYS", "HOURS")
    private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val INT = Regex("(?<![A-Za-z0-9_])[0-9][A-Za-z0-9_]*")
    private val CALL = Regex("\\b(listOf|setOf|mapOf|arrayOf|intArrayOf)\\s*[(<]")
    private const val MARK = '\u0001'

    /** Code per line: comments blanked, each string literal replaced by MARK n MARK; the literals in [strings]. */
    private fun strip(
        text: String,
        strings: MutableList<String>,
    ): List<String> = Lexer(text, strings).run().split("\n")

    private class Lexer(
        private val text: String,
        private val strings: MutableList<String>,
    ) {
        private val out = StringBuilder()
        private var i = 0
        private var depth = 0

        fun run(): String {
            while (i < text.length) {
                when {
                    depth > 0 -> {
                        insideComment()
                    }

                    text.startsWith("/*", i) -> {
                        depth = 1
                        i += 2
                    }

                    text.startsWith("//", i) -> {
                        while (i < text.length && text[i] != '\n') i++
                    }

                    text.startsWith("\"\"\"", i) -> {
                        rawString()
                    }

                    text[i] == '"' -> {
                        quoted('"')
                    }

                    text[i] == '\'' -> {
                        quoted('\'')
                    }

                    else -> {
                        out.append(text[i++])
                    }
                }
            }
            return out.toString()
        }

        private fun insideComment() {
            when {
                text.startsWith("/*", i) -> {
                    depth++
                    i += 2
                }

                text.startsWith("*/", i) -> {
                    depth--
                    i += 2
                }

                else -> {
                    if (text[i] == '\n') out.append('\n')
                    i++
                }
            }
        }

        private fun rawString() {
            val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it }
            strings += text.substring(i + 3, end)
            out.append(MARK).append(strings.size - 1).append(MARK)
            val stop = minOf(end + 3, text.length)
            out.append("\n".repeat(text.substring(i, stop).count { it == '\n' }))
            i = stop
        }

        private fun quoted(quote: Char) {
            var j = i + 1
            while (j < text.length && text[j] != quote && text[j] != '\n') j += if (text[j] == '\\') 2 else 1
            if (quote == '"') {
                strings += text.substring(i + 1, minOf(j, text.length))
                out.append(MARK).append(strings.size - 1).append(MARK)
            } else {
                out.append("''")
            }
            i = j + 1
        }
    }

    /** `file:line` of every finding in [text]; [file] is only the label. */
    fun scan(
        file: String,
        text: String,
    ): List<String> {
        val strings = ArrayList<String>()
        val found = ArrayList<String>()
        strip(text, strings).forEachIndexed { index, line ->
            for (statement in line.split(";")) {
                val names = IDENT.findAll(statement).map { it.value.replace("_", "").lowercase() }.toList()
                if (names.none { n -> NAMES.any { it in n } }) continue
                val literal =
                    strings
                        .filterIndexed {
                            n,
                            _,
                            ->
                            statement.contains("$MARK$n$MARK")
                        }.any { it in LITERALS }
                if (INT.containsMatchIn(statement.replace(Regex("$MARK[0-9]+$MARK"), "")) ||
                    CALL.containsMatchIn(statement) ||
                    literal
                ) {
                    found += "$file:${index + 1}"
                }
            }
        }
        return found.distinct()
    }

    fun scanTree(root: File): Pair<Int, List<String>> {
        var files = 0
        val findings = ArrayList<String>()
        for (module in MODULES) {
            val main = root.resolve("$module/src/main")
            if (!main.isDirectory) continue
            main
                .walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "kts", "java") }
                .forEach {
                    files++
                    findings +=
                        scan(it.relativeTo(root).path.replace(File.separatorChar, '/'), it.readText(Charsets.UTF_8))
                }
        }
        return files to findings
    }
}

/**
 * The scope clause of the gate (INV-T-5056 done-when): the test fails when the merge request adds a file under a
 * `src/main/kotlin` or `src/main/java` directory. The added files are read from git against the merge-request base,
 * so a file that already existed on main is never a finding.
 */
object CalendarAddedMainSources {
    private val MAIN = Regex("(^|/)src/main/(kotlin|java)/")

    /** The paths of [added] that lie under a `src/main/kotlin` or `src/main/java` directory. */
    fun offending(added: List<String>): List<String> = added.filter { MAIN.containsMatchIn(it.replace('\\', '/')) }

    private fun git(
        root: File,
        vararg args: String,
    ): String? {
        val process = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(false).start()
        val out = process.inputStream.bufferedReader().readText()
        process.errorStream.readBytes()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        return if (process.exitValue() == 0) out else null
    }

    /** The commit the merge request branches from: the CI diff base, else the merge base with origin/main. */
    fun base(
        root: File,
        env: Map<String, String>,
    ): String? =
        env["CI_MERGE_REQUEST_DIFF_BASE_SHA"]?.takeIf { it.isNotBlank() }
            ?: git(root, "merge-base", "HEAD", "origin/main")?.trim()?.takeIf { it.isNotEmpty() }

    /** Files added since [base]: committed, staged, modified in the tree, or untracked (renames count as adds). */
    fun added(
        root: File,
        base: String,
    ): List<String> {
        val committed = git(root, "diff", "--name-only", "--no-renames", "--diff-filter=A", base, "HEAD")
        val tree = git(root, "diff", "--name-only", "--no-renames", "--diff-filter=A", base)
        val untracked = git(root, "ls-files", "--others", "--exclude-standard")
        return listOf(committed, tree, untracked)
            .flatMap { checkNotNull(it) { "git failed in $root" }.lines() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    /**
     * The offending added files. Fails closed when the base cannot be resolved, except in the pipeline of the default
     * branch, where there is no merge request to check.
     */
    fun check(
        root: File,
        env: Map<String, String>,
    ): List<String> {
        val onDefaultBranch =
            env["CI"] != null && env["CI_COMMIT_BRANCH"] != null && env["CI_COMMIT_BRANCH"] == env["CI_DEFAULT_BRANCH"]
        if (onDefaultBranch) return emptyList()
        val base = checkNotNull(base(root, env)) { "the merge-request base is not resolvable; fetch origin/main" }
        return offending(added(root, base))
    }
}

class CalendarSeedNoCodeConstantTest {
    @Test
    fun `INV-AC-1727 no main source of the five modules holds a margin threshold frequency or window constant`() {
        val root = CalendarSeedFiles.repoRoot
        root.resolve("control-plane-adapters/src/main/kotlin").isDirectory shouldBe true
        val (files, findings) = CalendarNoCodeScan.scanTree(root)
        (files > 0) shouldBe true
        findings.joinToString("\n") shouldBe ""
    }

    @Test
    fun `INV-AC-1727 the probes of the scanner each fail naming the file and line`() {
        fun hit(line: String) = CalendarNoCodeScan.scan("F.kt", "package p\n$line\n") shouldBe listOf("F.kt:2")
        hit("const val SAFETY_MARGIN_DAYS = 3")
        hit("val alertThresholds = listOf(10, 5)")
        hit("val filingFrequency = \"MONTHLY\"")
        hit("val windows = intArrayOf(1, 2)")
        hit("val x = 1; val alertThreshold = 2")
        hit("/* c */ val safetyMargin = 3")
        hit("val a = \"x\"; val safetyMargin = \"DAYS\"")
    }

    @Test
    fun `INV-AC-1727 the look-alikes of the scanner pass`() {
        fun pass(text: String) = CalendarNoCodeScan.scan("F.kt", text) shouldBe emptyList()
        pass("val safetyMargin: SafetyMargin = contents.safetyMargin")
        pass("val retries = 3")
        pass("// const val SAFETY_MARGIN_DAYS = 3")
        pass("/* const val SAFETY_MARGIN_DAYS = 3\n /* nested */ val windows = listOf(1) */")
        pass("val message = \"safetyMargin 3 windows\"")
    }

    @Test
    fun `INV-AC-1727 the merge request adds no file under src main kotlin or src main java`() {
        CalendarAddedMainSources.check(CalendarSeedFiles.repoRoot, System.getenv()).joinToString("\n") shouldBe ""
    }

    @Test
    fun `INV-AC-1727 the added-file probe fails for files added under src main kotlin or java`() {
        CalendarAddedMainSources.offending(
            listOf(
                "control-plane-adapters/src/main/kotlin/p/A.kt",
                "control-plane-domain/src/main/java/p/B.java",
                "control-plane-adapters/src/main/resources/packs/calendar/sg-gst/contents.json",
                "control-plane-adapters/src/test/kotlin/p/C.kt",
                "control-plane-adapters/src/mainx/kotlin/p/D.kt",
            ),
        ) shouldBe
            listOf("control-plane-adapters/src/main/kotlin/p/A.kt", "control-plane-domain/src/main/java/p/B.java")
    }

    @Test
    fun `INV-AC-1727 a real git repository with a file added under src main kotlin is a finding`() {
        val dir = createTempDirectory("added-probe").toFile()
        try {
            fun run(vararg args: String) {
                val p =
                    ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t") + args)
                        .directory(dir)
                        .redirectErrorStream(true)
                        .start()
                p.inputStream.readBytes()
                check(p.waitFor() == 0) { "git ${args.toList()} failed" }
            }
            run("init", "-q")
            File(dir, "m/src/main/kotlin").mkdirs()
            File(dir, "m/src/main/kotlin/Old.kt").writeText("class Old\n")
            run("add", ".")
            run("commit", "-q", "-m", "base")
            val base = CalendarAddedMainSources.base(dir, mapOf("CI_MERGE_REQUEST_DIFF_BASE_SHA" to head(dir)))!!
            CalendarAddedMainSources.offending(CalendarAddedMainSources.added(dir, base)) shouldBe emptyList()
            File(dir, "m/src/main/kotlin/New.kt").writeText("class New\n")
            CalendarAddedMainSources.offending(CalendarAddedMainSources.added(dir, base)) shouldBe
                listOf("m/src/main/kotlin/New.kt")
            run("add", ".")
            run("commit", "-q", "-m", "mr")
            CalendarAddedMainSources.offending(CalendarAddedMainSources.added(dir, base)) shouldBe
                listOf("m/src/main/kotlin/New.kt")
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun head(dir: File): String {
        val p = ProcessBuilder("git", "rev-parse", "HEAD").directory(dir).start()
        return p.inputStream
            .bufferedReader()
            .readText()
            .trim()
            .also { p.waitFor() }
    }
}
