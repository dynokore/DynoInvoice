package com.dynokore.gst.controlPlane.adapters.migrations

import com.dynokore.platform.auditlog.AuditSchema
import com.dynokore.platform.outboxandqueue.OutboxSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The forward migration that restores U+FEFF in the blank checks Flyway built (INV-T-12238, INV-AC-12239; INV-REQ-854):
 * its text is the generator's own, read from the pinned platform modules, so a BOM move that changes the generator
 * fails here. Flyway drops U+FEFF from a script it reads, so the file holds the escape, never the character.
 */
class BlankCheckRepairMigrationTest {
    private val file = "V202610281000__repair_blank_checks.sql"
    private val directory: Path = Path.of(System.getProperty("gst.migrations.dir"))
    private val migration: String = Files.readString(directory.resolve(file))

    private val outbox = OutboxSchema.ddl("controlplane", "controlplane_runtime", "controlplane_sys")
    private val audit = AuditSchema.auditEntryDdl("controlplane", "controlplane_runtime")

    private val functions = listOf("full_sync_delete", "event_dead_letter_record")

    private val end = "revoke create on schema controlplane from controlplane_sys;"

    /** From `<keyword> controlplane.<name>(` to the end of its generator group. */
    private fun group(
        text: String,
        keyword: String,
        name: String,
    ): String {
        val start = text.indexOf("$keyword controlplane.$name(")
        require(start >= 0) { "no function $name" }
        val stop = text.indexOf(end, start)
        require(stop >= 0) { "the group of $name has no end" }
        return text.substring(start, stop + end.length)
    }

    private fun checkExpression(column: String): String {
        val match = Regex("""(?m)^ {4}$column text (?:not null )?check \((.*)\),$""").find(audit)
        return checkNotNull(match) { "no CHECK on $column" }.groupValues[1]
    }

    @Test
    fun `the file is printable ASCII and line feeds only, so Flyway drops nothing`() {
        assertTrue(migration.none { it.code == FEFF_CODE })
        assertEquals("", migration.filter { it != '\n' && it !in ' '..'~' })
    }

    @Test
    fun `each affected function is the generator group written create or replace`() {
        functions.forEach { name ->
            val expected =
                group(
                    outbox,
                    "create function",
                    name,
                ).replaceFirst("create function", "create or replace function")
            assertTrue("\\uFEFF" in expected, name)
            assertEquals(expected, group(migration, "create or replace function", name), name)
        }
        assertEquals(0, Regex("""(?mi)^create function\b""").findAll(migration).count())
        assertEquals(functions.size, Regex("""(?mi)^create or replace function\b""").findAll(migration).count())
        val positions = functions.map { migration.indexOf("create or replace function controlplane.$it(") }
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun `each CHECK constraint is dropped and added with the generator expression, then validated`() {
        assertEquals(
            CONSTRAINTS,
            Regex(
                """add constraint (\w+)""",
                RegexOption.IGNORE_CASE,
            ).findAll(migration).map { it.groupValues[1] }.toList(),
        )
        COLUMNS.forEach { column ->
            val name = "audit_entry_${column}_check"
            val expr = checkExpression(column)
            assertTrue("\\uFEFF" in expr, column)
            val alter =
                "alter table controlplane.audit_entry drop constraint $name, add constraint $name check ($expr) not valid;"
            assertEquals(1, migration.lines().count { it == alter }, column)
            val validate = "alter table controlplane.audit_entry validate constraint $name;"
            assertEquals(1, migration.lines().count { it == validate }, column)
            assertTrue(migration.indexOf(alter) < migration.indexOf(validate), column)
        }
        assertEquals(COLUMNS.size, Regex("""(?i)validate constraint""").findAll(migration).count())
        assertEquals(COLUMNS.size, Regex("""(?i)drop constraint""").findAll(migration).count())
    }

    @Test
    fun `the file begins with the lock timeout`() {
        assertTrue(migration.startsWith("SET lock_timeout"))
    }

    @Test
    fun `the file name is the CS-19 name and its version is present in the directory`() {
        val versions =
            Files
                .list(directory)
                .use { stream -> stream.map { it.fileName.toString() }.filter { it.endsWith(".sql") }.toList() }
                .associateBy { Regex("""^V(\d+)__""").find(it)!!.groupValues[1].toLong() }
        assertEquals(file, versions[202610281000L])
    }

    private companion object {
        const val FEFF_CODE = 0xFEFF
        val COLUMNS = listOf("actor", "action", "scope", "reason")
        val CONSTRAINTS = COLUMNS.map { "audit_entry_${it}_check" }
    }
}
