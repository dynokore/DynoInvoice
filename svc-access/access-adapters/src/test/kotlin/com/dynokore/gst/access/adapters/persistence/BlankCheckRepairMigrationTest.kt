package com.dynokore.gst.access.adapters.persistence

import com.dynokore.platform.configresolver.ConfigSchema
import com.dynokore.platform.outboxandqueue.OutboxSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The forward migration that restores U+FEFF in the blank checks Flyway built (INV-T-10861, INV-AC-10868): its text is
 * the generator's own, read from the pinned platform modules, so a BOM move that changes the generator fails here.
 */
class BlankCheckRepairMigrationTest {
    private val file = "V202610270900__repair_blank_checks.sql"

    private val migration: String =
        checkNotNull(
            javaClass.classLoader.getResourceAsStream("db/migration/$file"),
        ) { "$file is not on the classpath" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private val outbox = OutboxSchema.ddl("access", "access_runtime", "access_sys")
    private val config = ConfigSchema.ddl("access", "access_runtime", "access_sys", ConfigSchema.EventMode.OUTBOX)

    /** From `create function access.<name>(` to the `revoke create on schema` that ends its generator group. */
    private fun group(
        generator: String,
        name: String,
    ): String {
        val start = generator.indexOf("create function access.$name(")
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
        FUNCTIONS.forEach { (name, generator) ->
            val expected =
                group(if (generator == OUTBOX) outbox else config, name)
                    .replaceFirst("create function", "create or replace function")
            assertTrue("\\uFEFF" in expected, name)
            assertEquals(2, migration.split(expected).size, name)
        }
        assertEquals(0, Regex("""(?m)^create function\b""").findAll(migration).count())
        assertEquals(FUNCTIONS.size, Regex("""(?m)^create or replace function\b""").findAll(migration).count())
    }

    @Test
    fun `the function groups are in the file order of the V files`() {
        val positions = FUNCTIONS.map { (name, _) -> migration.indexOf("create or replace function access.$name(") }
        assertEquals(positions.sorted(), positions)
        assertTrue(positions.all { it >= 0 })
    }

    @Test
    fun `each blank CHECK constraint is dropped, re-added not valid with the generator expression, and validated`() {
        val tables = Regex("""create table access\.(\w+)""")
        val columns = Regex("""^\s+(\w+) .*? check \((.*)\),?$""")
        var table = ""
        val expected = mutableMapOf<String, Pair<String, String>>()
        config.lines().forEach { line ->
            tables.find(line)?.let { table = it.groupValues[1] }
            columns.find(line)?.takeIf { "FEFF" in line }?.let {
                expected["${table}_${it.groupValues[1]}_check"] = table to it.groupValues[2]
            }
        }
        assertEquals(CONSTRAINTS, expected.keys.toList())
        expected.forEach { (name, tableAndExpr) ->
            val (t, expr) = tableAndExpr
            val replace = "alter table access.$t drop constraint $name, add constraint $name check ($expr) not valid;"
            assertEquals(1, migration.lines().count { it == replace }, name)
            assertEquals(1, migration.lines().count { it == "alter table access.$t validate constraint $name;" }, name)
        }
        assertEquals(CONSTRAINTS.size, Regex("""add constraint (\w+) """).findAll(migration).count())
        assertEquals(
            CONSTRAINTS,
            Regex("""add constraint (\w+) """).findAll(migration).map { it.groupValues[1] }.toList(),
        )
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
        const val GROUP_END = "revoke create on schema access from access_sys;"
        const val OUTBOX = "outbox"
        val FUNCTIONS =
            listOf(
                "full_sync_delete" to OUTBOX,
                "event_dead_letter_record" to OUTBOX,
                "write_installation_setting" to "config",
                "config_purge_subject" to "config",
                "config_purge_tenant" to "config",
                "config_option_value_maximum" to "config",
            )
        val CONSTRAINTS =
            listOf(
                "config_setting_scope_id_check",
                "config_setting_set_by_check",
                "config_setting_reason_check",
                "config_installation_setting_set_by_check",
                "config_installation_setting_reason_check",
                "config_resolved_value_subject_ref_check",
                "config_relaxation_acknowledgement_scope_id_check",
                "config_relaxation_acknowledgement_role_check",
                "config_relaxation_acknowledgement_principal_check",
                "config_relaxation_acknowledgement_reason_check",
            )
    }
}
