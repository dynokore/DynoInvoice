package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.tenancykernel.TenantRlsMigration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * The migrations that hold platform-generated DDL are exactly what the pinned platform modules generate: an upgrade of
 * outbox-and-queue or tenancy-kernel that changes the text fails here until the migration is regenerated (TPL-12).
 * Tables are tenant-scoped under the signed-binding row policy of tenancy-kernel (INV-T-4156), never a hand-written
 * one. The platform lint checks the tenant column; this catalogue test checks the policy and FORCE ROW LEVEL SECURITY
 * half (coordinator ruling INV-Q-7420).
 */
class GeneratedMigrationsTest {
    private val outbox = "V202610210890__create_outbox.sql"
    private val triples = "V202610210900__create_identity_triple.sql"
    private val counterparties = "V202610220900__create_counterparty.sql"

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
     * not printable ASCII raw, the current one writes `\uXXXX`; the V files are never edited (INV-T-10865).
     */
    private fun escapeBlankCodePoints(text: String): String =
        BLANK_CODE_POINTS.fold(text) { acc, cp ->
            acc.replace(String(Character.toChars(cp)), "\\u" + "%04X".format(Locale.ROOT, cp))
        }

    @Test
    fun `the outbox migration is the DDL outbox-and-queue generates for the masterdata schema`() {
        val expected = OutboxSchema.ddl("masterdata", "masterdata_runtime", "masterdata_sys").trim()
        val text = migration(outbox)
        assertEquals(OUTBOX_RAW_FEFF, text.count { it == FEFF })
        assertEquals(expected, escapeBlankCodePoints(body(text)))
    }

    @Test
    fun `no migration creates the binding functions or the binding key table`() {
        listOf(outbox, triples, counterparties).forEach { file ->
            val text = migration(file)
            assertTrue("CREATE TABLE masterdata.binding_key" !in text, file)
            assertTrue("CREATE FUNCTION masterdata.bound_" !in text, file)
            assertTrue("SET ROLE" !in text, file)
        }
    }

    @Test
    fun `every service table carries the generated tenant policy and forced row-level security`() {
        val text = migration(triples)
        assertTrue(TenantRlsMigration.ddl("masterdata", "identity_triple", "tenant_isolation") in text)
        assertTrue("CREATE TABLE masterdata.identity_triple (" in text)
        assertTrue("tenant_id       uuid        NOT NULL" in text)
        val master = migration(counterparties)
        listOf(
            "counterparty",
            "counterparty_version",
            "counterparty_identifier",
            "counterparty_scheme_status",
            "structure_node",
        ).forEach { table ->
            assertTrue(TenantRlsMigration.ddl("masterdata", table, "tenant_isolation") in master, table)
            assertTrue("CREATE TABLE masterdata.$table (" in master, table)
        }
    }

    @Test
    fun `every table the service migration creates has a forced row policy`() {
        val text = migration(triples) + migration(counterparties)
        val tables = Regex("""(?i)create table\s+masterdata\.(\w+)""").findAll(text).map { it.groupValues[1] }.toList()
        assertTrue(tables.isNotEmpty())
        tables.forEach { table ->
            assertTrue("ALTER TABLE masterdata.$table ENABLE ROW LEVEL SECURITY;" in text, table)
            assertTrue("ALTER TABLE masterdata.$table FORCE ROW LEVEL SECURITY;" in text, table)
            assertTrue("CREATE POLICY tenant_isolation ON masterdata.$table" in text, table)
        }
    }

    private companion object {
        const val FEFF = '\uFEFF'
        const val OUTBOX_RAW_FEFF = 3
        val BLANK_CODE_POINTS =
            listOf(0x00A0, 0x1680, 0x2000, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF)
    }
}
