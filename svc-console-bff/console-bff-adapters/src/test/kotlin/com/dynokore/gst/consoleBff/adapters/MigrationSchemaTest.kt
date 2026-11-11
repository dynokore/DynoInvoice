package com.dynokore.gst.consoleBff.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException

/** The table checks on a PostgreSQL built by Flyway, and the schema-creation rules (INV-AC-14457). */
class MigrationSchemaTest {
    private val hash = "a".repeat(64)

    private fun insert(
        id: String = hash,
        csrf: String = hash,
        opened: String = "2026-11-10T10:00:00Z",
        seen: String = "2026-11-10T10:00:00Z",
    ) = ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c ->
        c.createStatement().execute(
            "insert into console.console_session values ('$id', '11111111-1111-1111-1111-111111111111', 'p', " +
                "'$opened', '$seen', 1, '$csrf')",
        )
    }

    private fun refused(block: () -> Unit) {
        (runCatching(block).exceptionOrNull() is SQLException) shouldBe true
    }

    private fun clean() = ConsoleDb.superuser().use { it.createStatement().execute("truncate console.console_session") }

    @Test
    fun `INV-AC-14457 a 63 and a 65 character hash and upper case hex are refused`() {
        refused { insert(id = "a".repeat(63)) }
        refused { insert(id = "a".repeat(65)) }
        refused { insert(id = "A".repeat(64)) }
        refused { insert(csrf = "A".repeat(64)) }
        refused { insert(csrf = "a".repeat(63)) }
    }

    @Test
    fun `INV-AC-14457 last_seen_at before opened_at is refused`() {
        refused { insert(seen = "2026-11-10T09:59:59Z") }
    }

    @Test
    fun `INV-AC-14457 a second row with the same hash is refused`() {
        clean()
        insert()
        refused { insert() }
        clean()
    }

    @Test
    fun `INV-AC-14457 no migration file contains create schema`() {
        val dir = Path.of("src/main/resources/db/migration")
        val files = Files.list(dir).use { it.toList() }
        (files.isNotEmpty()) shouldBe true
        files.filter { Files.readString(it).contains("create schema", ignoreCase = true) } shouldBe emptyList()
    }

    @Test
    fun `INV-AC-14457 the migration role holds no CREATE on the database and owns the schema`() {
        ConsoleDb.superuser().use { c ->
            val privilege = "select has_database_privilege('console_migrate', current_database(), 'CREATE')"
            c.createStatement().executeQuery(privilege).use {
                it.next()
                it.getBoolean(1) shouldBe false
            }
            val owner = "select schema_owner from information_schema.schemata where schema_name = 'console'"
            c.createStatement().executeQuery(owner).use {
                it.next()
                it.getString(1) shouldBe "console_migrate"
            }
        }
    }

    @Test
    fun `INV-AC-14457 the table is commented platform-scoped and has bound_tenant_id and no tenant_id column`() {
        ConsoleDb.superuser().use { c ->
            c.createStatement().executeQuery(
                "select obj_description('console.console_session'::regclass), " +
                    "(select string_agg(column_name, ',') from information_schema.columns " +
                    "where table_schema = 'console' and table_name = 'console_session')",
            ).use { rs ->
                rs.next() shouldBe true
                rs.getString(1) shouldBe "platform-scoped: no tenant data"
                val columns = rs.getString(2).split(",")
                (columns.contains("bound_tenant_id")) shouldBe true
                (columns.contains("tenant_id")) shouldBe false
            }
        }
    }
}
