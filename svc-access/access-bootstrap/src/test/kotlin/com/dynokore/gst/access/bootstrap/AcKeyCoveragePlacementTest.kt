package com.dynokore.gst.access.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * INV-T-26530 (INV-AC-26529): the real gate `gradle/gates/ac-key-coverage.gradle.kts` is run through the repository
 * wrapper over a fixture project in a temporary directory; the cases compare the exit status and whole lines of the
 * finding text. The fixture source is assembled so that no line of this file matches the gate's function pattern
 * except the cases themselves.
 */
class AcKeyCoveragePlacementTest {
    private val gate = File("../gradle/gates/ac-key-coverage.gradle.kts").canonicalFile
    private val wrapper = File("../gradlew").canonicalPath
    private val header = "criterion\ttask\twave\tmodule"
    private val modules = listOf("access-bootstrap", "access-bootstrap2", "access-bootstrap-x", "access-adapters")

    private class Result(val exit: Int, val lines: List<String>)

    private fun fn(name: String) = listOf("fu", "n `$name`() {}").joinToString("") + "\n"

    private fun run(
        table: String?,
        files: Map<String, String>,
    ): Result {
        val base = System.getenv("TMPDIR")?.let { File(it) } ?: File(System.getProperty("java.io.tmpdir"))
        val dir = Files.createTempDirectory(base.toPath(), "ackey-fixture").toFile()
        try {
            File(dir, "settings.gradle.kts").writeText("rootProject.name = \"fixture\"\n")
            File(dir, "build.gradle.kts").writeText("plugins { base }\napply(from = \"ac-key-coverage.gradle.kts\")\n")
            gate.copyTo(File(dir, "ac-key-coverage.gradle.kts"))
            modules.forEach { File(dir, "$it/src/test/kotlin").mkdirs() }
            if (table != null) File(dir, "ac-keys.tsv").writeText(table)
            files.forEach { (path, text) ->
                File(dir, path).also { it.parentFile.mkdirs() }.writeText(text)
            }
            val out = File(dir, "gradle.out")
            val process =
                ProcessBuilder(wrapper, "acKeyCoverage", "--no-daemon", "--console=plain", "--no-build-cache")
                    .directory(dir).redirectErrorStream(true).redirectOutput(out).start()
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                error("the fixture build timed out")
            }
            return Result(process.exitValue(), out.readLines().map { it.trim() })
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun row(module: String? = "access-bootstrap") =
        "INV-AC-9001\tINV-T-9001\t2" + module?.let { "\t$it" }.orEmpty() + "\n"

    private fun table(vararg rows: String) = header + "\n" + rows.joinToString("")

    private fun placed(
        path: String,
        name: String = "INV-AC-9001 case",
    ) = mapOf(path to fn(name))

    private fun misplaced(
        module: String,
        path: String,
    ) = "test 'INV-AC-9001 case' ($path:1) carries INV-AC-9001 in module $module, " +
        "but the row of INV-AC-9001 names module access-bootstrap"

    private val noTest =
        "row INV-AC-9001 (task INV-T-9001, wave 2) has no test whose name contains INV-AC-9001 as a whole token"

    private fun inNoRow(
        name: String,
        path: String,
        key: String,
    ) = "test '$name' ($path:1) holds $key, which is in no row of ac-keys.tsv"

    private fun badModule(
        line: String,
        cell: String,
    ) = "row 1 '$line': module '$cell' must name a directory at the repository root that holds a src/test tree"

    @Test
    fun `INV-AC-26529 case 1 the case in access-adapters fails and names key and both modules`() {
        val r = run(table(row()), placed("access-adapters/src/test/kotlin/P.kt"))
        assertThat(r.exit).isNotZero()
        assertThat(r.lines).contains(misplaced("access-adapters", "access-adapters/src/test/kotlin/P.kt"), noTest)
    }

    @Test
    fun `INV-AC-26529 case 2 the case in access-bootstrap passes`() {
        val r = run(table(row()), placed("access-bootstrap/src/test/kotlin/P.kt"))
        assertThat(r.exit).isZero()
    }

    @Test
    fun `INV-AC-26529 case 3a look-alike module access-bootstrap2 fails`() {
        val r = run(table(row()), placed("access-bootstrap2/src/test/kotlin/P.kt"))
        assertThat(r.exit).isNotZero()
        assertThat(r.lines).contains(misplaced("access-bootstrap2", "access-bootstrap2/src/test/kotlin/P.kt"), noTest)
    }

    @Test
    fun `INV-AC-26529 case 3b look-alike module access-bootstrap-x fails`() {
        val r = run(table(row()), placed("access-bootstrap-x/src/test/kotlin/P.kt"))
        assertThat(r.exit).isNotZero()
        assertThat(r.lines).contains(misplaced("access-bootstrap-x", "access-bootstrap-x/src/test/kotlin/P.kt"), noTest)
    }

    @Test
    fun `INV-AC-26529 case 3c package directory named like the module fails`() {
        val path = "access-adapters/src/test/kotlin/access-bootstrap/P.kt"
        val r = run(table(row()), placed(path))
        assertThat(r.exit).isNotZero()
        assertThat(r.lines).contains(misplaced("access-adapters", path), noTest)
    }

    @Test
    fun `INV-AC-26529 case 3d malformed module cells fail`() {
        listOf("access-bootstrap/", "Access-Bootstrap", "access-missing").forEach { cell ->
            val r = run(table(row(cell)), placed("access-bootstrap/src/test/kotlin/P.kt"))
            assertThat(r.exit).`as`(cell).isNotZero()
            assertThat(r.lines).`as`(cell).contains(badModule("INV-AC-9001\tINV-T-9001\t2\t$cell", cell))
        }
    }

    @Test
    fun `INV-AC-26529 case 4 the case in both modules fails on the access-adapters one`() {
        val files = placed("access-bootstrap/src/test/kotlin/P.kt") + placed("access-adapters/src/test/kotlin/P.kt")
        val r = run(table(row()), files)
        assertThat(r.exit).isNotZero()
        assertThat(r.lines).contains(misplaced("access-adapters", "access-adapters/src/test/kotlin/P.kt"))
        assertThat(r.lines).doesNotContain(noTest)
    }

    @Test
    fun `INV-AC-26529 case 5 legacy and malformed rows`() {
        val adapters = placed("access-adapters/src/test/kotlin/P.kt")
        assertThat(run(table(row(null)), adapters).exit).`as`("three cells").isZero()

        val five = run(table(row().trimEnd('\n') + "\textra\n"), placed("access-bootstrap/src/test/kotlin/P.kt"))
        assertThat(five.exit).`as`("five cells").isNotZero()
        assertThat(five.lines).contains(
            "row 1 'INV-AC-9001\tINV-T-9001\t2\taccess-bootstrap\textra' must have three or four tab-separated columns",
        )

        val old = run("criterion\ttask\twave\n" + row(null), adapters)
        assertThat(old.exit).`as`("old header").isNotZero()
        assertThat(old.lines).contains(
            "header must be 'criterion<TAB>task<TAB>wave<TAB>module', was 'criterion\ttask\twave'",
        )

        val empty = run(table(row("")), placed("access-bootstrap/src/test/kotlin/P.kt"))
        assertThat(empty.exit).`as`("empty cell").isNotZero()
        assertThat(empty.lines).contains(
            badModule("INV-AC-9001\tINV-T-9001\t2", ""),
        )
    }

    @Test
    fun `INV-AC-26529 case 6 the findings of the gate before the module cell are kept`() {
        val ok = "access-bootstrap/src/test/kotlin/P.kt"
        val none = run(table(row()), emptyMap())
        assertThat(none.exit).isNotZero()
        assertThat(none.lines).contains(noTest)

        val other = "access-bootstrap/src/test/kotlin/Q.kt"
        val unlisted = run(table(row()), placed(ok) + placed(other, "INV-AC-9002 other"))
        assertThat(unlisted.exit).isNotZero()
        assertThat(unlisted.lines).contains(inNoRow("INV-AC-9002 other", other, "INV-AC-9002"))

        val longer = run(table(row()), placed(ok, "INV-AC-90010 x"))
        assertThat(longer.exit).isNotZero()
        assertThat(longer.lines).contains(inNoRow("INV-AC-90010 x", ok, "INV-AC-90010"))

        val glued = run(table(row()), placed(ok, "INV-AC-9001x case"))
        assertThat(glued.exit).isNotZero()
        assertThat(glued.lines).contains(
            "test 'INV-AC-9001x case' ($ok:1) holds 'INV-AC-9001' that is not a whole token",
        )

        val disabled = run(table(row()), mapOf(ok to "@Disabled\n" + fn("INV-AC-9001 case")))
        assertThat(disabled.exit).isNotZero()
        assertThat(disabled.lines).contains(noTest)

        val missing = run(null, placed(ok))
        assertThat(missing.exit).isNotZero()
        assertThat(missing.lines).anyMatch {
            it.startsWith("Type 'Ac_key_coverage_gradle.AcKeyCoverage' property 'table' specifies file ") &&
                it.endsWith("ac-keys.tsv' which doesn't exist")
        }
    }
}
