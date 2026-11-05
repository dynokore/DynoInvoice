package com.dynokore.gst.access.adapters.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The forward migration that adds the report reference of a break-glass action reported as an event (INV-Q-9117 A). */
class BreakGlassReportRefMigrationTest {
    private val file = "V202611031100__add_break_glass_action_report_ref.sql"

    private val migration: String =
        checkNotNull(
            javaClass.classLoader.getResourceAsStream("db/migration/$file"),
        ) { "$file is not on the classpath" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun adds(sql: String): Boolean =
        Regex(
            "ADD COLUMN report_ref text\\s+CHECK " +
                "\\(report_ref IS NULL OR length\\(report_ref\\) BETWEEN 1 AND 64\\)",
        ).containsMatchIn(sql)

    private fun unique(sql: String): Boolean = Regex("""\bUNIQUE \(tenant_id, report_ref\)""").containsMatchIn(sql)

    @Test
    fun `the file adds the nullable column with a check of 1 to 64 and the unique pair`() {
        assertTrue(migration.startsWith("SET lock_timeout = '5s';"))
        assertTrue(adds(migration))
        assertTrue(unique(migration))
        assertEquals(1, Regex("""(?i)\bADD COLUMN\b""").findAll(migration).count())
    }

    @Test
    fun `changing the bound, the null alternative or the unique pair is caught`() {
        assertFalse(adds(migration.replace("BETWEEN 1 AND 64", "BETWEEN 1 AND 65")))
        assertFalse(adds(migration.replace("report_ref IS NULL OR ", "")))
        assertFalse(unique(migration.replace("UNIQUE (tenant_id, report_ref)", "UNIQUE (report_ref)")))
    }

    @Test
    fun `a second column is caught`() {
        val second = migration.replace(",\n    ADD CONSTRAINT", ",\n    ADD COLUMN other text,\n    ADD CONSTRAINT")
        assertEquals(2, Regex("""(?i)\bADD COLUMN\b""").findAll(second).count())
    }

    @Test
    fun `no policy or grant is touched`() {
        assertFalse(Regex("""(?i)\b(POLICY|GRANT|REVOKE|ROW LEVEL)\b""").containsMatchIn(migration))
        assertTrue(Regex("""(?i)\b(POLICY|GRANT)\b""").containsMatchIn(migration + " GRANT x"))
    }
}
