package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.platform.outboxandqueue.OutboxSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The forward migration that restores U+FEFF in the blank checks Flyway built (INV-T-10865, INV-AC-10872): its text is
 * the generator's own, read from the pinned platform modules, so a BOM move that changes the generator fails here.
 */
class BlankCheckRepairMigrationTest {
    private val file = "V202610270900__repair_blank_checks.sql"

    private val migration: String =
        checkNotNull(
            javaClass.classLoader.getResourceAsStream("db/migration/$file"),
        ) { "$file is not on the classpath" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private val outbox = OutboxSchema.ddl("masterdata", "masterdata_runtime", "masterdata_sys")

    /** From `create function masterdata.<name>(` to the `revoke create on schema` that ends its generator group. */
    private fun group(
        generator: String,
        name: String,
    ): String {
        val start = generator.indexOf("create function masterdata.$name(")
        require(start >= 0) { "the generator holds no function $name" }
        val end = generator.indexOf(GROUP_END, start)
        require(end >= 0) { "the generator group of $name has no end" }
        return generator.substring(start, end + GROUP_END.length)
    }

    @Test
    fun `the file is printable ASCII and line feeds only, so Flyway drops nothing`() {
        assertTrue(migration.none { it.code == FEFF_CODE })
        val bad = migration.filter { it != '\n' && it !in ' '..'~' }
        assertEquals("", bad)
    }

    @Test
    fun `each affected function is the generator group written create or replace`() {
        FUNCTIONS.forEach { name ->
            val expected =
                group(outbox, name)
                    .replaceFirst("create function", "create or replace function")
            assertTrue("\\uFEFF" in expected, name)
            assertEquals(2, migration.split(expected).size, name)
        }
        assertEquals(0, Regex("""(?m)^create function\b""").findAll(migration).count())
        assertEquals(FUNCTIONS.size, Regex("""(?m)^create or replace function\b""").findAll(migration).count())
    }

    @Test
    fun `the function groups are in the file order of the V files`() {
        val positions = FUNCTIONS.map { name -> migration.indexOf("create or replace function masterdata.$name(") }
        assertEquals(positions.sorted(), positions)
        assertTrue(positions.all { it >= 0 })
    }

    @Test
    fun `the file adds and drops no constraint`() {
        assertEquals(0, Regex("""(?i)\b(add|drop) constraint\b""").findAll(migration).count())
    }

    @Test
    fun `the file begins with the lock timeout`() {
        assertTrue(migration.startsWith("SET lock_timeout = '5s';"))
    }

    @Test
    fun `the file name is the CS-19 name and its version is the greatest in the directory`() {
        val directory = Path.of(checkNotNull(javaClass.classLoader.getResource("db/migration")).toURI())
        val versions =
            Files
                .list(directory)
                .use { stream ->
                    stream.map { it.fileName.toString() }.filter { it.endsWith(".sql") }.toList()
                }.associateBy { Regex("""^V(\d+)__""").find(it)!!.groupValues[1].toLong() }
        assertEquals(file, versions[versions.keys.max()])
        assertTrue(Regex("""^V\d{12}__[a-z]+(_[a-z]+)*\.sql$""").matches(file))
    }

    private companion object {
        const val FEFF_CODE = 0xFEFF
        const val GROUP_END = "revoke create on schema masterdata from masterdata_sys;"
        val FUNCTIONS = listOf("full_sync_delete", "event_dead_letter_record")
    }
}
