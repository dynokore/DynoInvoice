package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.platform.configresolver.ConfigSchema
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.serviceruntime.http.IdempotencySchema
import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantRlsMigration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

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

    /**
     * Flyway drops every U+FEFF from a script (INV-T-10836): the old generator wrote the ten blank code points that are
     * not printable ASCII raw, the current one writes `\uXXXX`; the V files are never edited (INV-T-10864).
     */
    private fun escapeBlankCodePoints(text: String): String =
        BLANK_CODE_POINTS.fold(text) { acc, cp ->
            acc.replace(String(Character.toChars(cp)), "\\u" + "%04X".format(Locale.ROOT, cp))
        }

    @Test
    fun `the outbox migration is the DDL outbox-and-queue generates for the tenancy schema`() {
        // the create migration predates the testmode column (outbox-and-queue 6.10.0): V202611041000 adds it
        val generated = OutboxSchema.ddl("tenancy", "tenancy_runtime", "tenancy_sys").trim()
        assertEquals(1, TESTMODE_COLUMN.findAll(generated).count())
        val expected = TESTMODE_COLUMN.replaceFirst(generated, "")
        val text = migration("V202610190930__create_outbox.sql")
        assertFalse("testmode" in text)
        assertEquals(OUTBOX_RAW_FEFF, text.count { it == FEFF })
        assertEquals(expected, escapeBlankCodePoints(body(text)))
    }

    @Test
    fun `the outbox testmode migration is the upgrade DDL outbox-and-queue generates for the tenancy schema`() {
        assertTrue(isTestModeUpgrade(statements(migration("V202611041000__outbox_testmode.sql"))))
    }

    private fun isTestModeUpgrade(statements: List<String>): Boolean =
        statements == listOf("SET lock_timeout = '5s';", OutboxSchema.testModeUpgradeDdl("tenancy") + ";")

    @Test
    fun `the outbox testmode upgrade check fails for each look-alike`() {
        val upgrade = OutboxSchema.testModeUpgradeDdl("tenancy") + ";"
        val lookAlikes =
            listOf(
                listOf("SET lock_timeout = '5s';", upgrade.replace("tenancy.outbox", "tenancy.outbox2")),
                listOf("SET lock_timeout = '5s';", upgrade.replace("default false", "default true")),
                listOf("SET lock_timeout = '5s';", upgrade, "select 1;"),
            )
        assertTrue(isTestModeUpgrade(listOf("SET lock_timeout = '5s';", upgrade)))
        lookAlikes.forEach { assertFalse(isTestModeUpgrade(it), it.toString()) }
    }

    /** The non-blank, non-comment lines of a migration. */
    private fun statements(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("--") }

    @Test
    fun `the idempotency migration is the DDL service-runtime generates for the tenancy schema`() {
        val expected = IdempotencySchema.ddl("tenancy", "tenancy_runtime", "tenancy_sys").trim()
        assertEquals(expected, body(migration("V202610190940__create_idempotency.sql")))
    }

    @Test
    fun `the config-resolver migration is the DDL config-resolver generates for the tenancy schema`() {
        val expected = ConfigSchema.ddl("tenancy", "tenancy_runtime", "tenancy_sys").trim()
        val text = migration("V202610221050__create_config_resolver.sql")
        assertEquals(CONFIG_RAW_FEFF, text.count { it == FEFF })
        assertEquals(expected, escapeBlankCodePoints(body(text)))
    }

    @Test
    fun `every service table carries the generated tenant policy`() {
        val file = "V202610190910__create_structure_tables.sql"
        val text = migration(file)
        listOf("tenant", "tenant_group", "entity", "business_unit", "short_code").forEach { name ->
            val table = SqlIdentifier.require(name, "table")
            assertTrue(TenantRlsMigration.ddl("tenancy", table, "tenant_isolation") in text, "$file: $table")
            assertTrue("CREATE TABLE tenancy.$table (" in text, "$file: $table")
        }
    }

    private companion object {
        val TESTMODE_COLUMN = Regex(",\\s*testmode boolean not null default false")
        const val FEFF = '\uFEFF'
        const val OUTBOX_RAW_FEFF = 3
        const val CONFIG_RAW_FEFF = 18
        val BLANK_CODE_POINTS =
            listOf(0x00A0, 0x1680, 0x2000, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF)
    }
}
