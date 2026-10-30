package com.dynokore.gst.submission.acceptance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The queue-table guard over the real sources, and the probes of INV-T-7275 over scratch trees (never committed). */
class QueueTableGuardTest {
    private val adapters = "submission-adapters"
    private val migrations = QueueTableGuard.MIGRATION_DIRECTORY

    private fun realRoot(): Path {
        var directory = Path.of("").toAbsolutePath()
        while (!Files.exists(directory.resolve("settings.gradle.kts"))) directory = directory.parent
        return directory
    }

    private fun write(
        root: Path,
        relative: String,
        text: String,
    ) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    /** A scratch service: one module holding a migration that names the queue table, and one clean class. */
    private fun scratch(root: Path): Path {
        write(root, "settings.gradle.kts", "include(\n    \"$adapters\",\n)\n")
        write(root, "$migrations/V1__x.sql", "create table submission.work_queue (seq bigint);")
        write(root, "$adapters/src/main/kotlin/Clean.kt", "class Clean")
        return root
    }

    private fun findings(root: Path) =
        QueueTableGuard.findings(root, QueueTableGuard.modules(Files.readString(root.resolve("settings.gradle.kts"))))

    @Test
    fun `the real main source sets name the queue table nowhere but in the migrations`() {
        val root = realRoot()
        val modules = QueueTableGuard.modules(Files.readString(root.resolve("settings.gradle.kts")))
        assertTrue(modules.contains(adapters), "the modules of settings.gradle.kts: $modules")
        assertEquals(emptyList<String>(), QueueTableGuard.findings(root, modules))
    }

    @Test
    fun `the scratch service passes as it is`(
        @TempDir dir: Path,
    ) = assertEquals(emptyList<String>(), findings(scratch(dir)))

    @Test
    fun `probe the text work_queue in a main class fails`(
        @TempDir dir: Path,
    ) {
        write(scratch(dir), "$adapters/src/main/kotlin/Probe.kt", "val t = \"work_queue\"")
        assertEquals(listOf("$adapters/src/main/kotlin/Probe.kt contains work_queue"), findings(dir))
    }

    @Test
    fun `probe the text queue_item in a main class fails`(
        @TempDir dir: Path,
    ) {
        write(scratch(dir), "$adapters/src/main/kotlin/Probe.kt", "// queue_item")
        assertEquals(listOf("$adapters/src/main/kotlin/Probe.kt contains queue_item"), findings(dir))
    }

    @Test
    fun `probe the jOOQ table WORK_QUEUE in a main class fails`(
        @TempDir dir: Path,
    ) {
        write(scratch(dir), "$adapters/src/main/kotlin/Probe.kt", "val t = WorkQueue.WORK_QUEUE")
        assertTrue(findings(dir).contains("$adapters/src/main/kotlin/Probe.kt contains WORK_QUEUE"))
    }

    @Test
    fun `probe the jOOQ table class import in a main class fails`(
        @TempDir dir: Path,
    ) {
        write(scratch(dir), "$adapters/src/main/kotlin/Probe.kt", "import a.b.jooq.tables.WorkQueue")
        assertEquals(listOf("$adapters/src/main/kotlin/Probe.kt uses the jOOQ table WorkQueue"), findings(dir))
    }

    @Test
    fun `probe the text work_queue in a test class passes`(
        @TempDir dir: Path,
    ) {
        write(scratch(dir), "$adapters/src/test/kotlin/Probe.kt", "val t = \"work_queue\"")
        write(dir, "$adapters/src/integrationTest/kotlin/Probe.kt", "val t = \"work_queue\"")
        write(dir, "$adapters/build/generated/Probe.kt", "val t = \"work_queue\"")
        assertEquals(emptyList<String>(), findings(dir))
    }

    @Test
    fun `probe a copy of the migration directory holding work_queue fails`(
        @TempDir dir: Path,
    ) {
        write(scratch(dir), "$adapters/src/main/resources/db/migration-copy/V1__x.sql", "work_queue")
        assertEquals(
            listOf("$adapters/src/main/resources/db/migration-copy/V1__x.sql contains work_queue"),
            findings(dir),
        )
    }

    @Test
    fun `probe work_queue in a migration passes`(
        @TempDir dir: Path,
    ) {
        write(scratch(dir), "$migrations/V2__y.sql", "alter table submission.work_queue add column x int;")
        assertEquals(emptyList<String>(), findings(dir))
    }

    @Test
    fun `probe an emptied scan directory set fails`(
        @TempDir dir: Path,
    ) {
        scratch(dir)
        assertFalse(QueueTableGuard.findings(dir, emptyList()).isEmpty())
        assertEquals(
            listOf("the scanned directory set holds no file", "the allowlisted $migrations is not in the scan"),
            QueueTableGuard.findings(dir, emptyList()),
        )
    }

    @Test
    fun `probe a scan without the migration directory fails`(
        @TempDir dir: Path,
    ) {
        write(dir, "settings.gradle.kts", "include(\"m\")")
        write(dir, "m/src/main/kotlin/A.kt", "class A")
        assertEquals(listOf("the allowlisted $migrations is not in the scan"), findings(dir))
    }

    @Test
    fun `an include in a comment names no module`() {
        assertEquals(listOf("a", "b"), QueueTableGuard.modules("include(\"a\", // \"x\"\n \"b\")\n// include(\"c\")"))
    }
}
