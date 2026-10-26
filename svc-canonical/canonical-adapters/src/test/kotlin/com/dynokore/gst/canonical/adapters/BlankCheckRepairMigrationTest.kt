package com.dynokore.gst.canonical.adapters

import com.dynokore.platform.outboxandqueue.OutboxSchema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.util.Locale

/**
 * The forward migration that restores U+FEFF in the blank checks Flyway built (INV-T-10862, INV-AC-10869; INV-REQ-854):
 * its text is the generator's own, read from the pinned platform module, so a BOM move that changes the generator
 * fails here.
 */
class BlankCheckRepairMigrationTest {
    private val file = "V202610270900__repair_blank_checks.sql"

    private val migration: String =
        checkNotNull(
            javaClass.classLoader.getResourceAsStream("db/migration/$file"),
        ) { "$file is not on the classpath" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private val outbox = OutboxSchema.ddl("canonical", "canonical_runtime", "canonical_sys")

    /** From `create function canonical.<name>(` to the `revoke create on schema` that ends its generator group. */
    private fun group(
        text: String,
        name: String,
        keyword: String = "create function",
    ): String {
        val start = text.indexOf("$keyword canonical.$name(")
        require(start >= 0) { "no function $name" }
        val end = text.indexOf(GROUP_END, start)
        require(end >= 0) { "the group of $name has no end" }
        return text.substring(start, end + GROUP_END.length)
    }

    private fun repaired(name: String): String =
        group(outbox, name).replaceFirst("create function", "create or replace function")

    private fun escapeBlankClass(text: String): String =
        BLANK_CODE_POINTS.fold(text) { acc, cp ->
            acc.replace(String(Character.toChars(cp)), "\\u%04X".format(Locale.ROOT, cp))
        }

    @Test
    fun `the V1 file holds the generator groups of the affected functions with the blank class written raw`() {
        val v1 =
            File("src/main/resources/db/migration/V1__canonical_schema.sql").readText(Charsets.UTF_8)
        FUNCTIONS.forEach { name ->
            assertEquals(group(outbox, name), escapeBlankClass(group(v1, name)), name)
        }
        assertEquals(3, v1.count { it.code == FEFF_CODE })
    }

    @Test
    fun `the file is printable ASCII and line feeds only, so Flyway drops nothing`() {
        assertTrue(migration.none { it.code == FEFF_CODE })
        assertEquals("", migration.filter { it != '\n' && it !in ' '..'~' })
    }

    @Test
    fun `each affected function is the generator group written create or replace`() {
        FUNCTIONS.forEach { name ->
            val expected = repaired(name)
            assertTrue("\\uFEFF" in expected, name)
            assertEquals(expected, group(migration, name, "create or replace function"), name)
        }
        assertEquals(0, Regex("""(?m)^create function\b""").findAll(migration).count())
        assertEquals(FUNCTIONS.size, Regex("""(?m)^create or replace function\b""").findAll(migration).count())
        val positions = FUNCTIONS.map { migration.indexOf("create or replace function canonical.$it(") }
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun `the file adds and drops no constraint`() {
        assertFalse("add constraint" in migration)
        assertFalse("drop constraint" in migration)
    }

    @Test
    fun `the file begins with the lock timeout`() {
        assertTrue(migration.startsWith("SET lock_timeout"))
    }

    @Test
    fun `the file name is the CS-19 name and no later migration redefines the repaired functions`() {
        val directory = Path.of("src/main/resources/db/migration")
        val files =
            Files
                .list(directory)
                .use { stream -> stream.filter { it.fileName.toString().endsWith(".sql") }.toList() }
                .associateBy { Regex("""^V(\d+)__""").find(it.fileName.toString())!!.groupValues[1].toLong() }
        assertEquals(file, files[REPAIR_VERSION]?.fileName.toString())
        files.filterKeys { it > REPAIR_VERSION }.values.forEach { later ->
            FUNCTIONS.forEach { name ->
                assertFalse(redefines(Files.readString(later), name), "${later.fileName} $name")
            }
        }
    }

    /** True when [text] creates, alters or drops `canonical.<name>`, whatever the case, quoting and spacing. */
    private fun redefines(
        text: String,
        name: String,
    ): Boolean {
        val flat = text.replace("\"", "").lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ")
        return Regex("""(create|alter|drop) (or replace )?function (if exists )?canonical ?\. ?$name\b""")
            .containsMatchIn(flat)
    }

    @Test
    fun `the redefinition matcher detects every form and none of the look-alikes`() {
        val forms =
            listOf(
                "CREATE OR REPLACE FUNCTION canonical.full_sync_delete(",
                "create function canonical.\"event_dead_letter_record\"(",
                "create function\ncanonical.full_sync_delete(",
                "alter function canonical.full_sync_delete",
                "drop function if exists canonical . event_dead_letter_record",
            )
        forms.forEach { form -> assertTrue(FUNCTIONS.any { redefines(form, it) }, form) }
        val lookAlikes =
            listOf(
                "create function canonical.full_sync_delete_v2(",
                "create function other.full_sync_delete(",
                "select canonical.full_sync_delete(",
            )
        lookAlikes.forEach { text -> assertFalse(FUNCTIONS.any { redefines(text, it) }, text) }
    }

    private data class Facts(
        val owner: String,
        val secdef: Boolean,
        val config: String,
        val acl: String,
        val source: String,
    )

    private fun alterTypes(name: String): String =
        Regex("""alter function canonical\.$name\(([^)]*)\) owner to (\w+);""").find(repaired(name))!!.groupValues[1]

    private fun alterOwner(name: String): String =
        Regex("""alter function canonical\.$name\(([^)]*)\) owner to (\w+);""").find(repaired(name))!!.groupValues[2]

    private fun regproc(name: String) = "canonical.$name(${alterTypes(name)})"

    private fun facts(
        connection: Connection,
        name: String,
    ): Facts =
        connection
            .prepareStatement(
                "select pg_get_userbyid(proowner), prosecdef, coalesce(array_to_string(proconfig, ','), ''), " +
                    "coalesce(proacl::text, ''), prosrc from pg_proc where oid = ?::regprocedure",
            ).use { statement ->
                statement.setString(1, regproc(name))
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "no function $name" }
                    Facts(
                        rows.getString(1),
                        rows.getBoolean(2),
                        rows.getString(3),
                        rows.getString(4),
                        rows.getString(5),
                    )
                }
            }

    @Test
    fun `each affected function has the owner, mode, search path and ACL of its group and the escape in its source`() {
        CanonicalDb.superuser().use { connection ->
            FUNCTIONS.forEach { name ->
                val text = repaired(name)
                val found = facts(connection, name)
                assertEquals(alterOwner(name), found.owner, name)
                assertEquals("security definer" in text, found.secdef, name)
                val path = Regex("""set search_path = ([^$]*?) as \$\$""").find(text)!!.groupValues[1]
                assertEquals("search_path=$path", found.config, name)
                assertTrue("canonical_runtime=X/${found.owner}" in found.acl, found.acl)
                assertFalse(found.acl.split(',').any { it.startsWith("=") || it.startsWith("{=") }, found.acl)
                assertTrue("\\uFEFF" in found.source, name)
                assertTrue(found.source.none { it.code == FEFF_CODE }, name)
            }
        }
    }

    private fun refused(block: (Connection) -> Unit) {
        CanonicalDb.connect(CanonicalDb.RUNTIME, CanonicalDb.RUNTIME_PASSWORD).use { connection ->
            val failure = shouldThrow<SQLException> { block(connection) }
            failure.sqlState shouldBe "22023"
        }
    }

    private fun count(table: String): Long =
        CanonicalDb.superuser().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select count(*) from canonical.$table").use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
        }

    private fun callDeadLetter(
        connection: Connection,
        consumer: String,
    ) = connection
        .prepareStatement("select canonical.event_dead_letter_record(?, 't', 1, null, 'r', 'raw')")
        .use { statement ->
            statement.setString(1, consumer)
            statement.executeQuery().use { it.next() }
        }

    @Test
    fun `full_sync_delete refuses a consumer of only U+FEFF and writes nothing, and a passes`() {
        val before = count("consumer_sequence")
        refused { connection ->
            connection.prepareStatement("select canonical.full_sync_delete(?, 's')").use { statement ->
                statement.setString(1, "$FEFF")
                statement.executeQuery()
            }
        }
        assertEquals(before, count("consumer_sequence"))
        CanonicalDb.connect(CanonicalDb.RUNTIME, CanonicalDb.RUNTIME_PASSWORD).use { connection ->
            connection.prepareStatement("select canonical.full_sync_delete('a', 's')").use { statement ->
                statement.executeQuery().use { assertTrue(it.next()) }
            }
        }
    }

    @Test
    fun `event_dead_letter_record refuses a consumer of only U+FEFF and writes nothing, and a passes`() {
        val before = count("event_dead_letter")
        refused { callDeadLetter(it, "$FEFF$FEFF") }
        assertEquals(before, count("event_dead_letter"))
        CanonicalDb.connect(CanonicalDb.RUNTIME, CanonicalDb.RUNTIME_PASSWORD).use { connection ->
            assertTrue(callDeadLetter(connection, "a"))
        }
        assertEquals(before + 1, count("event_dead_letter"))
    }

    private companion object {
        const val REPAIR_VERSION = 202610270900L
        const val FEFF_CODE = 0xFEFF
        val FEFF: Char = FEFF_CODE.toChar()
        const val GROUP_END = "revoke create on schema canonical from canonical_sys;"
        val FUNCTIONS = listOf("full_sync_delete", "event_dead_letter_record")
        val BLANK_CODE_POINTS =
            listOf(0x00A0, 0x1680, 0x2000, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF)
    }
}
