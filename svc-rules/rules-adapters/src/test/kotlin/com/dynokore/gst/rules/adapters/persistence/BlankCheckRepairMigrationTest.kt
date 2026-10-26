package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.rulepackkernel.PackRegistryMigration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The forward migration that restores U+FEFF in the blank checks Flyway built (INV-T-10867, INV-AC-10874; INV-REQ-854):
 * its text is the generator's own, read from the pinned platform modules, so a BOM move that changes the generator
 * fails here.
 */
class BlankCheckRepairMigrationTest {
    private val file = "V202610270900__repair_blank_checks.sql"

    private val migration: String =
        checkNotNull(
            javaClass.classLoader.getResourceAsStream("db/migration/$file"),
        ) { "$file is not on the classpath" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private val outbox = OutboxSchema.ddl("rules", "rules_runtime", "rules_sys")
    private val kernel = PackRegistryMigration.ddl("rules", "pack_artefact", "rules_runtime", "rules_sys")

    /** One affected function: where its generator text is, how its keyword is written and where its group ends. */
    private data class Fn(
        val name: String,
        val create: String,
        val end: String,
        val tail: String? = null,
    ) {
        /** The keyword of the repair: `create or replace function`, in the case the generator writes it. */
        val replaced: String =
            create
                .replace("create function", "create or replace function")
                .replace("CREATE FUNCTION", "CREATE OR REPLACE FUNCTION")
    }

    private val functions =
        listOf(
            Fn("full_sync_delete", "create function", "revoke create on schema rules from rules_sys;"),
            Fn("event_dead_letter_record", "create function", "revoke create on schema rules from rules_sys;"),
            // a trigger function the migration login owns: no owner statement, its revoke is a statement of its own
            Fn(
                "pack_artefact_registered_by_insert",
                "CREATE FUNCTION",
                "\$\$;",
                "REVOKE EXECUTE ON FUNCTION rules.pack_artefact_registered_by_insert() FROM PUBLIC;",
            ),
            Fn("register_platform_pack", "CREATE FUNCTION", "REVOKE CREATE ON SCHEMA rules FROM rules_sys;"),
        )

    private fun source(fn: Fn): String = if (fn.create == "create function") outbox else kernel

    /** From `<keyword> rules.<name>(` to the end of its generator group. */
    private fun group(
        text: String,
        keyword: String,
        fn: Fn,
    ): String {
        val start = text.indexOf("$keyword rules.${fn.name}(")
        require(start >= 0) { "no function ${fn.name}" }
        val end = text.indexOf(fn.end, start)
        require(end >= 0) { "the group of ${fn.name} has no end" }
        return text.substring(start, end + fn.end.length)
    }

    private fun repaired(fn: Fn): String = group(source(fn), fn.create, fn).replaceFirst(fn.create, fn.replaced)

    private val checkLine = Regex("""registered_by +text NOT NULL CHECK \((.*)\),\n""")

    @Test
    fun `the file is printable ASCII and line feeds only, so Flyway drops nothing`() {
        assertTrue(migration.none { it.code == FEFF_CODE })
        assertEquals("", migration.filter { it != '\n' && it !in ' '..'~' })
    }

    @Test
    fun `each affected function is the generator group written create or replace`() {
        functions.forEach { fn ->
            val expected = repaired(fn)
            assertTrue("\\uFEFF" in expected, fn.name)
            assertEquals(expected, group(migration, fn.replaced, fn), fn.name)
            fn.tail?.let {
                assertTrue(it in source(fn), fn.name)
                assertEquals(1, migration.windowed(it.length).count { w -> w == it }, fn.name)
            }
        }
        assertEquals(0, Regex("""(?mi)^create function\b""").findAll(migration).count())
        assertEquals(functions.size, Regex("""(?mi)^create or replace function\b""").findAll(migration).count())
        val positions = functions.map { migration.indexOf("${it.replaced} rules.${it.name}(") }
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun `the one CHECK constraint is dropped and added with the generator expression, then validated`() {
        val expr = checkLine.find(kernel)!!.groupValues[1]
        assertTrue("\\uFEFF" in expr)
        assertEquals(
            CONSTRAINTS,
            Regex("""add constraint (\w+)""", RegexOption.IGNORE_CASE)
                .findAll(migration)
                .map {
                    it.groupValues[1]
                }.toList(),
        )
        val alter =
            "ALTER TABLE rules.pack_artefact DROP CONSTRAINT $CONSTRAINT, ADD CONSTRAINT $CONSTRAINT CHECK ($expr) NOT VALID;"
        assertEquals(1, migration.lines().count { it == alter })
        assertEquals(
            1,
            migration.lines().count {
                it ==
                    "ALTER TABLE rules.pack_artefact VALIDATE CONSTRAINT $CONSTRAINT;"
            },
        )
        assertEquals(1, Regex("""(?i)validate constraint""").findAll(migration).count())
        assertTrue(migration.indexOf(alter) < migration.indexOf("VALIDATE CONSTRAINT"))
    }

    @Test
    fun `the file begins with the lock timeout`() {
        assertTrue(migration.startsWith("SET lock_timeout = '5s';\n"))
    }

    @Test
    fun `the file name is the CS-19 name and its version is the greatest in the directory`() {
        val directory = Path.of("src/main/resources/db/migration")
        val versions =
            Files
                .list(directory)
                .use { stream ->
                    stream.map { it.fileName.toString() }.filter { it.endsWith(".sql") }.toList()
                }.associateBy { Regex("""^V(\d+)__""").find(it)!!.groupValues[1].toLong() }
        assertEquals(file, versions[versions.keys.max()])
        assertEquals(202610270900L, versions.keys.max())
    }

    private companion object {
        const val FEFF_CODE = 0xFEFF
        const val CONSTRAINT = "pack_artefact_registered_by_check"
        val CONSTRAINTS = listOf(CONSTRAINT)
    }
}
