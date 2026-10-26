package com.dynokore.gst.tenancy.bootstrap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The blank checks of the database built by Flyway from all migrations refuse a value made only of U+FEFF
 * (INV-T-10864, INV-AC-10871): Flyway drops a raw U+FEFF, so only the forward migration `V202610270900` restores it.
 */
class BlankCheckRepairDbTest {
    private val migration: String by lazy {
        Files.readString(
            Path.of(
                "..",
                "tenancy-adapters",
                "src",
                "main",
                "resources",
                "db",
                "migration",
                "V202610270900__repair_blank_checks.sql",
            ),
        )
    }

    /** From `create or replace function tenancy.<name>(` to the `revoke create on schema` that ends its group. */
    private fun group(name: String): String {
        val start = migration.indexOf("create or replace function tenancy.$name(")
        assertTrue(start >= 0, name)
        return migration.substring(start, migration.indexOf(GROUP_END, start) + GROUP_END.length)
    }

    @Test
    fun `each affected function has the owner, security, search path and privileges its generator group sets`() {
        BoundaryFixture.asSuperuser { connection ->
            FUNCTIONS.forEach { name ->
                val group = group(name)
                val owner = captured("""alter function tenancy\.$name\([^)]*\) owner to (\w+);""", group)
                val searchPath = captured("""set search_path = ([^$]*?) as \$\$""", group)
                val grantee = captured("""grant execute on function tenancy\.$name\([^)]*\) to (\w+);""", group)
                assertNotNull(owner, name)
                assertNotNull(searchPath, name)
                assertEquals("tenancy_runtime", grantee, name)
                assertTrue("revoke all on function tenancy.$name(" in group, name)
                connection
                    .prepareStatement(
                        "select pg_get_userbyid(p.proowner), p.prosecdef, p.proconfig, p.prosrc, " +
                            "has_function_privilege('tenancy_runtime', p.oid, 'EXECUTE'), " +
                            "(select count(*) from aclexplode(p.proacl) a where a.grantee = 0) " +
                            "from pg_proc p where p.oid = ?::regproc",
                    ).use { statement ->
                        statement.setString(1, "tenancy.$name")
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
                            assertTrue(rows.getBoolean(5), "$name EXECUTE for tenancy_runtime")
                            assertEquals(0, rows.getInt(6), "$name EXECUTE for PUBLIC")
                        }
                    }
            }
        }
    }

    @Test
    fun `each blank CHECK constraint holds the escaped U+FEFF and refuses a value made only of it`() {
        BoundaryFixture.asSuperuser { connection ->
            CONSTRAINTS.forEach { (table, column) ->
                val name = "${table}_${column}_check"
                val definition =
                    connection
                        .prepareStatement(
                            "select pg_get_constraintdef(c.oid) from pg_constraint c " +
                                "where c.conrelid = ?::regclass and c.conname = ? and c.contype = 'c' " +
                                "and c.convalidated",
                        ).use { statement ->
                            statement.setString(1, "tenancy.$table")
                            statement.setString(2, name)
                            statement.executeQuery().use { rows ->
                                assertTrue(rows.next(), name)
                                rows.getString(1)
                            }
                        }
                assertTrue(SIX in definition, "$name definition holds the six characters")
                assertFalse(FEFF in definition, "$name definition holds no U+FEFF")
                val refused = insertRefused(connection, table, column, FEFF)
                assertEquals("23514", refused.sqlState, name)
                assertTrue("check constraint \"$name\"" in refused.message.orEmpty(), name)
                assertEquals(
                    null,
                    insertRefusedOrNull(connection, table, column, ZERO_WIDTH_SPACE),
                    "$name accepts U+200B",
                )
                assertEquals(null, insertRefusedOrNull(connection, table, column, "a"), "$name accepts a letter")
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
                        "insert into tenancy.consumer_sequence values ('$FEFF', 'src', 's', gen_random_uuid(), 1)",
                    )
                }
                val before = count(connection, "tenancy.consumer_sequence")
                val deadLettersBefore = count(connection, "tenancy.event_dead_letter")
                assertEquals("22023", refusal(connection, "select tenancy.full_sync_delete(?, 'src')", FEFF)?.sqlState)
                assertEquals("22023", refusal(connection, "select tenancy.full_sync_delete('a', ?)", FEFF)?.sqlState)
                assertEquals(
                    "22023",
                    refusal(
                        connection,
                        "select tenancy.event_dead_letter_record(?, 't', 1, null, 'r', 'raw')",
                        FEFF,
                    )?.sqlState,
                )
                assertEquals(before, count(connection, "tenancy.consumer_sequence"))
                assertEquals(deadLettersBefore, count(connection, "tenancy.event_dead_letter"))
                assertEquals(null, refusal(connection, "select tenancy.full_sync_delete(?, 'b')", "a"))
                assertEquals(
                    null,
                    refusal(connection, "select tenancy.event_dead_letter_record(?, 't', 1, null, 'r', 'raw')", "a"),
                )
                assertEquals(deadLettersBefore + 1, count(connection, "tenancy.event_dead_letter"))
            } finally {
                connection.rollback()
            }
        }
    }

    @Test
    fun `the installation functions refuse an actor, a reason or an option key made only of U+FEFF`() {
        BoundaryFixture.asSuperuser { connection ->
            connection.autoCommit = false
            try {
                val install =
                    "select * from tenancy.write_installation_setting('VP-01', array['k'], null, current_date, ?, " +
                        "'{}'::jsonb, null)"
                val maximum = "select tenancy.config_option_value_maximum('VP-01', ?)"
                bind(connection, PLATFORM_TENANT, FEFF)
                assertEquals("42501", refusal(connection, install, "r")?.sqlState)
                bind(connection, PLATFORM_TENANT, "u")
                assertEquals("22023", refusal(connection, install, FEFF)?.sqlState)
                assertTrue(
                    refusal(connection, install, FEFF)?.message?.contains("are required") == true,
                    "the reason refusal is the blank check",
                )
                // a letter passes the reason check: the call stops later, on the policy row, with another message
                assertFalse(refusal(connection, install, "r")?.message?.contains("are required") == true)
                assertEquals("22023", refusal(connection, maximum, FEFF)?.sqlState)
                assertEquals(null, refusal(connection, maximum, "a"))
            } finally {
                connection.rollback()
            }
        }
    }

    @Test
    fun `the purge functions refuse an actor made only of U+FEFF and delete nothing`() {
        BoundaryFixture.asSuperuser { connection ->
            connection.autoCommit = false
            try {
                val tenant = UUID.randomUUID()
                connection.createStatement().use {
                    it.execute(
                        "insert into tenancy.config_resolved_value (id, tenant_id, subject_type, subject_ref, " +
                            "vp_key, transaction_date, option_keys, source, resolved_at) " +
                            "values (gen_random_uuid(), '$tenant', 'st', 'sr', 'VP-01', current_date, " +
                            "array['k'], 'DEFAULT', now())",
                    )
                }
                val before = count(connection, "tenancy.config_resolved_value")
                val purgeSubject = "select tenancy.config_purge_subject('st', 'sr')"
                val purgeTenant = "select * from tenancy.config_purge_tenant('$tenant')"
                bind(connection, tenant, FEFF)
                assertEquals("42501", refusal(connection, purgeSubject)?.sqlState)
                assertEquals("42501", refusal(connection, purgeTenant)?.sqlState)
                assertEquals(before, count(connection, "tenancy.config_resolved_value"))
                bind(connection, tenant, "u")
                assertEquals(null, refusal(connection, purgeSubject))
                assertEquals(before - 1, count(connection, "tenancy.config_resolved_value"))
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

    /** Binds [tenant] and [actor] as a signing runner does, for the rest of the transaction (INV-Q-7418). */
    private fun bind(
        connection: Connection,
        tenant: Any,
        actor: String,
    ) {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(actor.toByteArray(Charsets.UTF_8))
        val payload = "$tenant.$encoded.${System.currentTimeMillis() / 1000 + 600}"
        val key = SecretKeySpec(TestSecrets.BINDING_KEY.toByteArray(), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        val digest = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        val signature = digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
        connection.prepareStatement("select set_config('app.binding_sig', ?, true)").use {
            it.setString(1, "$payload.$signature")
            it.execute()
        }
    }

    private fun insertRefused(
        connection: Connection,
        table: String,
        column: String,
        value: String,
    ): SQLException =
        checkNotNull(insertRefusedOrNull(connection, table, column, value)) { "$table.$column accepted it" }

    private fun captured(
        pattern: String,
        text: String,
    ): String? = Regex(pattern).find(text)?.groupValues?.get(1)

    /** One insert of a valid row with [value] in [column], rolled back; the refusal, or null when it is accepted. */
    private fun insertRefusedOrNull(
        connection: Connection,
        table: String,
        column: String,
        value: String,
    ): SQLException? {
        val row = VALID_ROWS.getValue(table)
        val columns = row.keys.toList()
        val values = columns.joinToString(", ") { if (it == column) "?" else row.getValue(it) }
        connection.autoCommit = false
        try {
            val sql = "insert into tenancy.$table (${columns.joinToString(", ")}) values ($values)"
            connection.prepareStatement(sql).use {
                it.setString(1, value)
                it.execute()
            }
            return null
        } catch (e: SQLException) {
            return e
        } finally {
            connection.rollback()
            connection.autoCommit = true
        }
    }

    private companion object {
        const val GROUP_END = "revoke create on schema tenancy from tenancy_sys;"
        const val PLATFORM_TENANT = "ffffffff-ffff-ffff-ffff-ffffffffffff"
        const val FEFF_CODE = 0xFEFF
        const val ZERO_WIDTH_SPACE_CODE = 0x200B
        val FEFF: String = FEFF_CODE.toChar().toString()
        val ZERO_WIDTH_SPACE: String = ZERO_WIDTH_SPACE_CODE.toChar().toString()

        /** The six characters backslash, u, F, E, F, F that the regex of the check reads as the code point. */
        const val SIX = "\\" + "uFEFF"

        val FUNCTIONS =
            listOf(
                "full_sync_delete",
                "event_dead_letter_record",
                "write_installation_setting",
                "config_purge_subject",
                "config_purge_tenant",
                "config_option_value_maximum",
            )

        /** The default name PostgreSQL gives an unnamed column CHECK: `<table>_<column>_check`. */
        data class Check(
            val table: String,
            val column: String,
        ) {
            val name: String get() = "${table}_${column}_check"
        }

        private const val ACK = "config_relaxation_acknowledgement"

        val CONSTRAINTS =
            listOf(
                Check("config_setting", "scope_id"),
                Check("config_setting", "set_by"),
                Check("config_setting", "reason"),
                Check("config_installation_setting", "set_by"),
                Check("config_installation_setting", "reason"),
                Check("config_resolved_value", "subject_ref"),
                Check(ACK, "scope_id"),
                Check(ACK, "role"),
                Check(ACK, "principal"),
                Check(ACK, "reason"),
            )

        /** A valid row of each table, one `column = SQL` per line; the column under test is replaced by a parameter. */
        val VALID_ROWS: Map<String, Map<String, String>> =
            mapOf(
                "config_setting" to
                    row(
                        """
                        id = gen_random_uuid()
                        tenant_id = gen_random_uuid()
                        vp_key = 'VP-01'
                        scope_level = 'TENANT'
                        scope_id = 'x'
                        option_keys = array['k']
                        source = 'OVERRIDE'
                        effective_from = current_date
                        set_by = 'u'
                        reason = 'r'
                        recorded_at = now()
                        """,
                    ),
                "config_installation_setting" to
                    row(
                        """
                        id = gen_random_uuid()
                        vp_key = 'VP-01'
                        option_keys = array['k']
                        effective_from = current_date
                        set_by = 'u'
                        reason = 'r'
                        recorded_at = now()
                        """,
                    ),
                "config_resolved_value" to
                    row(
                        """
                        id = gen_random_uuid()
                        tenant_id = gen_random_uuid()
                        subject_type = 'st'
                        subject_ref = 'x'
                        vp_key = 'VP-01'
                        transaction_date = current_date
                        option_keys = array['k']
                        source = 'DEFAULT'
                        resolved_at = now()
                        """,
                    ),
                ACK to
                    row(
                        """
                        id = gen_random_uuid()
                        relaxation_id = gen_random_uuid()
                        tenant_id = gen_random_uuid()
                        vp_key = 'VP-01'
                        scope_level = 'TENANT'
                        scope_id = 'x'
                        option_keys = array['k']
                        role = 'r'
                        principal = 'p'
                        effective_from = current_date
                        reason = 'r'
                        acknowledged_at = now()
                        """,
                    ),
            )

        private fun row(lines: String): Map<String, String> =
            lines
                .trimIndent()
                .lines()
                .filter { it.isNotBlank() }
                .associate { it.substringBefore(" = ").trim() to it.substringAfter(" = ").trim() }
    }
}
