package com.dynokore.gst.access.adapters.persistence

import com.dynokore.platform.outboxandqueue.OutboxSchema
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
    fun `the outbox migration is the DDL outbox-and-queue generates for the access schema`() {
        val expected = OutboxSchema.ddl("access", "access_runtime", "access_sys").trim()
        assertEquals(expected, body(migration("V202610190900__create_outbox.sql")))
    }

    @Test
    fun `the binding migration runs the generated binding functions as the sys role`() {
        val text = migration("V202610190850__create_binding.sql")
        assertTrue(BindingKeyMigration.ddl("access", "access_sys", "access_runtime").trim() in text)
        assertTrue("SET ROLE access_sys;" in text && "RESET ROLE;" in text)
    }

    @Test
    fun `every service table carries the generated tenant policy`() {
        val tables =
            mapOf(
                "V202610190910__create_identity_and_grant.sql" to
                    mapOf(
                        "identity" to "CREATE TABLE access.identity (",
                        "role_grant" to "CREATE TABLE access.role_grant (",
                    ),
                "V202610190920__create_revocation_and_verification_keys.sql" to
                    mapOf(
                        "revocation_entry" to "CREATE TABLE access.revocation_entry (",
                        "revocation_head" to "CREATE TABLE access.revocation_head (",
                        "verification_key" to "CREATE TABLE access.verification_key (",
                    ),
            )
        tables.forEach { (file, names) ->
            val text = migration(file)
            names.forEach { (table, create) ->
                assertTrue(TenantRlsMigration.ddl("access", table, "tenant_isolation") in text, "$file: $table")
                assertTrue(create in text, "$file: $table")
            }
        }
    }
}
