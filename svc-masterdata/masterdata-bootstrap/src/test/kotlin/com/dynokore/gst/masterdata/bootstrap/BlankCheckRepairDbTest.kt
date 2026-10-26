package com.dynokore.gst.masterdata.bootstrap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException

/**
 * The blank checks of the database built by Flyway from all migrations refuse a value made only of U+FEFF
 * (INV-T-10865, INV-AC-10872): Flyway drops a raw U+FEFF, so only the forward migration `V202610270900` restores it.
 */
class BlankCheckRepairDbTest {
    private val migration: String by lazy {
        Files.readString(
            Path.of(
                "..",
                "masterdata-adapters",
                "src",
                "main",
                "resources",
                "db",
                "migration",
                "V202610270900__repair_blank_checks.sql",
            ),
        )
    }

    /** From `create or replace function masterdata.<name>(` to the `revoke create on schema` that ends its group. */
    private fun group(name: String): String {
        val start = migration.indexOf("create or replace function masterdata.$name(")
        assertTrue(start >= 0, name)
        return migration.substring(start, migration.indexOf(GROUP_END, start) + GROUP_END.length)
    }

    @Test
    fun `each affected function has the owner, security, search path and privileges its generator group sets`() {
        BoundaryFixture.asSuperuser { connection ->
            FUNCTIONS.forEach { name ->
                val group = group(name)
                val owner = captured("""alter function masterdata\.$name\([^)]*\) owner to (\w+);""", group)
                val searchPath = captured("""set search_path = ([^$]*?) as \$\$""", group)
                val grantee = captured("""grant execute on function masterdata\.$name\([^)]*\) to (\w+);""", group)
                assertNotNull(owner, name)
                assertNotNull(searchPath, name)
                assertEquals("masterdata_runtime", grantee, name)
                assertTrue("revoke all on function masterdata.$name(" in group, name)
                connection
                    .prepareStatement(
                        "select pg_get_userbyid(p.proowner), p.prosecdef, p.proconfig, p.prosrc, " +
                            "has_function_privilege('masterdata_runtime', p.oid, 'EXECUTE'), " +
                            "(select count(*) from aclexplode(p.proacl) a where a.grantee = 0) " +
                            "from pg_proc p where p.oid = ?::regproc",
                    ).use { statement ->
                        statement.setString(1, "masterdata.$name")
                        statement.executeQuery().use { rows ->
                            assertTrue(rows.next(), name)
                            assertEquals(owner, rows.getString(1), "$name owner")
                            assertEquals("security definer" in group, rows.getBoolean(2), "$name prosecdef")
                            assertEquals(
                                listOf("search_path=$searchPath"),
                                (rows.getArray(3).array as Array<*>).map { it.toString() },
                                "$name proconfig",
                            )
                            val source = rows.getString(4)
                            assertTrue(SIX in source, "$name prosrc holds the six characters")
                            assertFalse(FEFF in source, "$name prosrc holds no U+FEFF")
                            assertTrue(rows.getBoolean(5), "$name EXECUTE for masterdata_runtime")
                            assertEquals(0, rows.getInt(6), "$name EXECUTE for PUBLIC")
                        }
                    }
            }
        }
    }

    @Test
    fun `the outbox functions refuse a consumer made only of U+FEFF and write nothing`() {
        BoundaryFixture.asSuperuser { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use {
                    it.execute(
                        "insert into masterdata.consumer_sequence values ('$FEFF', 'src', 's', gen_random_uuid(), 1)",
                    )
                }
                val before = count(connection, "masterdata.consumer_sequence")
                val deadLettersBefore = count(connection, "masterdata.event_dead_letter")
                assertEquals(
                    "22023",
                    refusal(connection, "select masterdata.full_sync_delete(?, 'src')", FEFF)?.sqlState,
                )
                assertEquals("22023", refusal(connection, "select masterdata.full_sync_delete('a', ?)", FEFF)?.sqlState)
                assertEquals(
                    "22023",
                    refusal(
                        connection,
                        "select masterdata.event_dead_letter_record(?, 't', 1, null, 'r', 'raw')",
                        FEFF,
                    )?.sqlState,
                )
                assertEquals(before, count(connection, "masterdata.consumer_sequence"))
                assertEquals(deadLettersBefore, count(connection, "masterdata.event_dead_letter"))
                assertEquals(null, refusal(connection, "select masterdata.full_sync_delete(?, 'b')", "a"))
                assertEquals(
                    null,
                    refusal(connection, "select masterdata.event_dead_letter_record(?, 't', 1, null, 'r', 'raw')", "a"),
                )
                assertEquals(deadLettersBefore + 1, count(connection, "masterdata.event_dead_letter"))
            } finally {
                connection.rollback()
            }
        }
    }

    private fun count(
        connection: Connection,
        table: String,
    ): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery("select count(*) from $table").use {
                it.next()
                it.getLong(1)
            }
        }

    /** Runs [sql] with [argument] under a savepoint; the refusal, or null when the call passes. */
    private fun refusal(
        connection: Connection,
        sql: String,
        argument: String? = null,
    ): SQLException? {
        val savepoint = connection.setSavepoint()
        return try {
            connection.prepareStatement(sql).use { statement ->
                argument?.let { statement.setString(1, it) }
                statement.execute()
            }
            connection.releaseSavepoint(savepoint)
            null
        } catch (e: SQLException) {
            connection.rollback(savepoint)
            e
        }
    }

    private fun captured(
        pattern: String,
        text: String,
    ): String? = Regex(pattern).find(text)?.groupValues?.get(1)

    private companion object {
        const val GROUP_END = "revoke create on schema masterdata from masterdata_sys;"
        const val FEFF_CODE = 0xFEFF
        val FEFF: String = FEFF_CODE.toChar().toString()

        /** The six characters backslash, u, F, E, F, F that the regex of the check reads as the code point. */
        const val SIX = "\\" + "uFEFF"

        val FUNCTIONS = listOf("full_sync_delete", "event_dead_letter_record")
    }
}
