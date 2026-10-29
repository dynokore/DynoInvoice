package com.dynokore.gst.controlPlane.adapters

import com.dynokore.platform.auditlog.AuditSchema
import com.dynokore.platform.outboxandqueue.OutboxSchema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * The blank checks of the Flyway-built `controlplane` schema refuse a value made only of U+FEFF (INV-T-12238,
 * INV-AC-12239; INV-REQ-854). The database is the one PostgresDb builds from every migration through Flyway, as
 * controlplane_migrate; Flyway drops U+FEFF from V202610210810 and V202610271110, so without V202610281000 every probe
 * here fails. Expected facts are derived from the generator's own statement groups, never typed in.
 */
class BlankCheckRepairDbIT {
    private val outbox = OutboxSchema.ddl("controlplane", "controlplane_runtime", "controlplane_sys")
    private val audit = AuditSchema.auditEntryDdl("controlplane", "controlplane_runtime")

    /** An affected function: its name, its regprocedure and the end of its generator group. */
    private data class Fn(
        val name: String,
        val regproc: String,
    )

    private val functions =
        listOf(
            Fn("full_sync_delete", "controlplane.full_sync_delete(text, text)"),
            Fn(
                "event_dead_letter_record",
                "controlplane.event_dead_letter_record(text, text, bigint, uuid, text, text)",
            ),
        )

    /** The generator's statements for [fn]: the create statement through the revoke that ends its group. */
    private fun statements(fn: Fn): String {
        val end = "revoke create on schema controlplane from controlplane_sys;"
        val start = outbox.indexOf("create function controlplane.${fn.name}(")
        require(start >= 0) { "no function ${fn.name}" }
        val stop = outbox.indexOf(end, start)
        require(stop >= 0) { "the group of ${fn.name} has no end" }
        return outbox.substring(start, stop + end.length)
    }

    private data class Facts(
        val owner: String,
        val secdef: Boolean,
        val config: String,
        val acl: String,
        val source: String,
    )

    private fun facts(
        connection: Connection,
        fn: Fn,
    ): Facts =
        connection
            .prepareStatement(
                "select pg_get_userbyid(proowner), prosecdef, coalesce(array_to_string(proconfig, ','), ''), " +
                    "coalesce(proacl::text, ''), prosrc from pg_proc where oid = ?::regprocedure",
            ).use { statement ->
                statement.setString(1, fn.regproc)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "no function ${fn.name}" }
                    Facts(
                        rows.getString(1),
                        rows.getBoolean(2),
                        rows.getString(3),
                        rows.getString(4),
                        rows.getString(5),
                    )
                }
            }

    private fun runtime(): Connection = PostgresDb.runtimeDataSource.connection

    private fun count(table: String): Long =
        PostgresDb.adminConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select count(*) from controlplane.$table").use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    private fun sqlState(failure: Throwable): String? =
        generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    private fun message(failure: Throwable): String =
        generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")

    private fun refused(
        state: String,
        block: () -> Unit,
    ): Throwable {
        val failure = shouldThrow<Throwable> { block() }
        sqlState(failure) shouldBe state
        return failure
    }

    @Test
    fun `each affected function has the owner, mode, search path and ACL of its group and the escape in its source`() {
        PostgresDb.adminConnection().use { connection ->
            functions.forEach { fn ->
                val text = statements(fn)
                val found = facts(connection, fn)
                val owner = Regex("""(?i)alter function [^;]* owner to (\w+);""").find(text)!!.groupValues[1]
                assertEquals(owner, found.owner, fn.name)
                assertEquals("security definer" in text.lowercase(), found.secdef, fn.name)
                val path = Regex("""(?i)set search_path = (.*?) as \$\$""").find(text)!!.groupValues[1]
                assertEquals("search_path=$path", found.config, fn.name)
                val acl =
                    found.acl
                        .trim('{', '}')
                        .split(',')
                        .filter { it.isNotEmpty() }
                // REVOKE ... FROM PUBLIC leaves no `=X/owner` entry; GRANT EXECUTE ... TO controlplane_runtime adds one
                val revokes = "from public" in text.lowercase()
                assertEquals(revokes, acl.none { it.startsWith("=") } && acl.isNotEmpty(), "${fn.name} $acl")
                val grants = "to controlplane_runtime" in text.lowercase()
                assertEquals(grants, "controlplane_runtime=X/${found.owner}" in acl, "${fn.name} $acl")
                assertTrue("\\uFEFF" in found.source, fn.name)
                assertTrue(found.source.none { it.code == FEFF_CODE }, fn.name)
            }
        }
    }

    private fun insertAudit(
        connection: Connection,
        column: String,
        value: String,
    ): Result<Int> {
        connection.autoCommit = false
        try {
            return runCatching {
                val values = mutableMapOf("actor" to "a", "action" to "x", "scope" to "s", "reason" to "r")
                values[column] = value
                connection
                    .prepareStatement(
                        "insert into controlplane.audit_entry (id, tenant_id, entity_ref, recorded_at, source, " +
                            "subject, " +
                            "instance_id, correlation_id, causation_id, actor, action, scope, reason) values " +
                            "(?::uuid, ?::uuid, 'e', now(), 's', 'sub', 'i', 'c', 'k', ?, ?, ?, ?)",
                    ).use { statement ->
                        statement.setString(1, UUID.randomUUID().toString())
                        statement.setString(2, UUID.randomUUID().toString())
                        statement.setString(3, values.getValue("actor"))
                        statement.setString(4, values.getValue("action"))
                        statement.setString(5, values.getValue("scope"))
                        statement.setString(6, values.getValue("reason"))
                        statement.executeUpdate()
                    }
            }
        } finally {
            connection.rollback()
            connection.autoCommit = true
        }
    }

    @Test
    fun `each CHECK constraint holds the escape and refuses a value of only U+FEFF with 23514`() {
        PostgresDb.adminConnection().use { connection ->
            COLUMNS.forEach { column ->
                val name = "audit_entry_${column}_check"
                // the generator's create table holds the escape in this column's check
                assertTrue("\\uFEFF" in audit.lines().single { it.startsWith("    $column text") }, column)
                connection
                    .prepareStatement(
                        "select pg_get_constraintdef(oid), convalidated from pg_constraint " +
                            "where conrelid = 'controlplane.audit_entry'::regclass and conname = ?",
                    ).use { statement ->
                        statement.setString(1, name)
                        statement.executeQuery().use { rows ->
                            assertTrue(rows.next(), "constraint $name")
                            assertTrue("\\uFEFF" in rows.getString(1), name)
                            assertTrue(rows.getString(1).none { it.code == FEFF_CODE }, name)
                            assertTrue(rows.getBoolean(2), name)
                        }
                    }
                // the superuser bypasses row-level security: the CHECK is what is under test here
                val refused = insertAudit(connection, column, "$FEFF").exceptionOrNull()
                assertEquals("23514", sqlState(refused!!), column)
                assertTrue(name in message(refused), message(refused))
                val twice = insertAudit(connection, column, "$FEFF$FEFF").exceptionOrNull()
                assertEquals("23514", sqlState(twice!!), column)
                assertTrue(insertAudit(connection, column, "\u2060").isSuccess, column)
            }
        }
    }

    @Test
    fun `full_sync_delete refuses a consumer of only U+FEFF and writes nothing, and a passes`() {
        val before = count("consumer_sequence")
        runtime().use { connection ->
            refused("22023") {
                connection.prepareStatement("select controlplane.full_sync_delete(?, 's')").use { statement ->
                    statement.setString(1, "$FEFF")
                    statement.executeQuery()
                }
            }
            assertEquals(before, count("consumer_sequence"))
            connection.autoCommit = false
            try {
                connection.prepareStatement("select controlplane.full_sync_delete('a', 's')").use { statement ->
                    statement.executeQuery().use { assertTrue(it.next()) }
                }
            } finally {
                connection.rollback()
            }
        }
    }

    private fun callDeadLetter(
        connection: Connection,
        consumer: String,
    ) = connection
        .prepareStatement("select controlplane.event_dead_letter_record(?, 't', 1, null, 'r', 'raw')")
        .use { statement ->
            statement.setString(1, consumer)
            statement.executeQuery().use { it.next() }
        }

    @Test
    fun `event_dead_letter_record refuses a consumer of only U+FEFF and writes nothing, and a passes`() {
        val before = count("event_dead_letter")
        runtime().use { connection ->
            refused("22023") { callDeadLetter(connection, "$FEFF$FEFF") }
            assertEquals(before, count("event_dead_letter"))
            connection.autoCommit = false
            try {
                assertTrue(callDeadLetter(connection, "a"))
            } finally {
                connection.rollback()
            }
        }
        assertEquals(before, count("event_dead_letter"))
    }

    private companion object {
        const val FEFF_CODE = 0xFEFF
        val FEFF: Char = FEFF_CODE.toChar()
        val COLUMNS = listOf("actor", "action", "scope", "reason")
    }
}
