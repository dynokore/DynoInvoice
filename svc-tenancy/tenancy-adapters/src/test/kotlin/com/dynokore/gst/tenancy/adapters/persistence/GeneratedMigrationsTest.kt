package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.serviceruntime.http.IdempotencySchema
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.TenantRlsMigration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The migrations that hold platform-generated DDL are exactly what the pinned platform modules generate: an upgrade of
 * outbox-and-queue or tenancy-kernel that changes the text fails here until the migration is regenerated (TPL-12).
 * Tables are tenant-scoped under the signed-binding row policy of tenancy-kernel (INV-T-4156), never a hand-written
 * one.
 */
class GeneratedMigrationsTest {
    private fun migration(name: String): String =
        checkNotNull(
            javaClass.classLoader.getResourceAsStream("db/migration/$name"),
        ) { "$name is not on the classpath" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun body(text: String): String =
        text
            .lines()
            .dropWhile { it.startsWith("--") }
            .joinToString("\n")
            .trim()

    @Test
    fun `the outbox migration is the DDL outbox-and-queue generates for the tenancy schema`() {
        val expected = OutboxSchema.ddl("tenancy", "tenancy_runtime", "tenancy_sys").trim()
        assertEquals(expected, body(migration("V202610190930__create_outbox.sql")))
    }

    @Test
    fun `the binding migration runs the generated binding functions as the sys role`() {
        val text = migration("V202610190900__create_binding_functions.sql")
        assertTrue(BindingKeyMigration.ddl("tenancy", "tenancy_sys", "tenancy_runtime").trim() in text)
        assertTrue("set role tenancy_sys;" in text && "reset role;" in text)
    }

    @Test
    fun `the idempotency migration is the DDL service-runtime generates for the tenancy schema`() {
        val expected = IdempotencySchema.ddl("tenancy", "tenancy_runtime", "tenancy_sys").trim()
        assertEquals(expected, body(migration("V202610190940__create_idempotency.sql")))
    }

    @Test
    fun `every service table carries the generated tenant policy`() {
        val file = "V202610190910__create_structure_tables.sql"
        val text = migration(file)
        listOf("tenant", "tenant_group", "registered_entity", "business_unit", "short_code").forEach { table ->
            assertTrue(TenantRlsMigration.ddl("tenancy", table, "tenant_isolation") in text, "$file: $table")
            assertTrue("CREATE TABLE tenancy.$table (" in text, "$file: $table")
        }
    }
}
