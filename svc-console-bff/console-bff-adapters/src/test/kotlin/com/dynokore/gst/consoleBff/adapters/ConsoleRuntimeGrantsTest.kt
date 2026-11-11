package com.dynokore.gst.consoleBff.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException

/**
 * The effective write privileges of the `console_runtime` role on a live PostgreSQL: no write grant outside the schema
 * `console`, and an INSERT into another service's schema is refused (INV-AC-1921; OWASP ASVS V4.1.3, NIST SP 800-53
 * AC-6). The test reads no SQL file text.
 */
class ConsoleRuntimeGrantsTest {
    private val writeListSql =
        """
        select n.nspname, c.relname
        from pg_class c join pg_namespace n on n.oid = c.relnamespace
        where c.relkind in ('r', 'p')
          and n.nspname not in ('pg_catalog', 'information_schema', 'pg_toast')
          and (has_table_privilege('console_runtime', c.oid, 'INSERT')
            or has_table_privilege('console_runtime', c.oid, 'UPDATE')
            or has_table_privilege('console_runtime', c.oid, 'DELETE')
            or has_table_privilege('console_runtime', c.oid, 'TRUNCATE'))
        order by 1, 2
        """.trimIndent()

    private fun exec(
        c: Connection,
        sql: String,
    ) = c.createStatement().use { it.execute(sql) }

    private fun writeList(): List<Pair<String, String>> =
        ConsoleDb.superuser().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(writeListSql).use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
                }
            }
        }

    private fun sqlState(block: () -> Unit): String? = (runCatching(block).exceptionOrNull() as? SQLException)?.sqlState

    private fun runtimeInsert(table: String) {
        val c = ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD)
        c.use { exec(it, "insert into $table values (1)") }
    }

    private fun prepareForeign() =
        ConsoleDb.superuser().use {
            exec(it, "create schema if not exists submission")
            exec(it, "create table if not exists submission.t (id int)")
            exec(it, "create schema if not exists console_x")
            exec(it, "create table if not exists console_x.t (id int)")
        }

    @Test
    fun `INV-AC-1921 console_runtime holds no write grant on any table outside the console schema`() {
        prepareForeign()
        val observed = writeList()
        println("INV-AC-1921 observed write list of console_runtime: $observed")
        observed.filter { it.first != "console" } shouldBe emptyList()
    }

    @Test
    fun `INV-AC-1921 console_runtime INSERT into submission t fails with SQLSTATE 42501`() {
        prepareForeign()
        sqlState { runtimeInsert("submission.t") } shouldBe "42501"
    }

    @Test
    fun `INV-AC-1921 look-alike schema console_x refuses INSERT with 42501`() {
        prepareForeign()
        sqlState { runtimeInsert("console_x.t") } shouldBe "42501"
        writeList().filter { it.first == "console_x" } shouldBe emptyList()
    }

    @Test
    fun `INV-AC-1921 positive control a console table is writable and appears in the write list`() {
        ConsoleDb.connect(ConsoleDb.MIGRATE, ConsoleDb.MIGRATE_PASSWORD).use {
            exec(it, "create table if not exists console.grants_probe (id int)")
        }
        try {
            sqlState { runtimeInsert("console.grants_probe") } shouldBe null
            val observed = writeList()
            println("INV-AC-1921 observed write list with control: $observed")
            (("console" to "grants_probe") in observed) shouldBe true
        } finally {
            ConsoleDb.connect(ConsoleDb.MIGRATE, ConsoleDb.MIGRATE_PASSWORD).use {
                exec(it, "drop table if exists console.grants_probe")
            }
        }
    }
}
