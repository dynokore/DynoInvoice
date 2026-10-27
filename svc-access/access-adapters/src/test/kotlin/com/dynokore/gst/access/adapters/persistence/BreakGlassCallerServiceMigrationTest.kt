package com.dynokore.gst.access.adapters.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The forward migration that adds the verified calling service to a recorded break-glass action (INV-Q-9113 A). */
class BreakGlassCallerServiceMigrationTest {
    private val file = "V202610290900__add_break_glass_action_caller_service.sql"

    private val migration: String =
        checkNotNull(
            javaClass.classLoader.getResourceAsStream("db/migration/$file"),
        ) { "$file is not on the classpath" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun adds(sql: String): Boolean =
        Regex(
            """ADD COLUMN caller_service text\s+CHECK \(caller_service IS NULL OR length\(caller_service\) BETWEEN 1 AND 64\)""",
        ).containsMatchIn(sql)

    @Test
    fun `the file adds the nullable column with a check of 1 to 64 characters`() {
        assertTrue(adds(migration))
        assertEquals(1, Regex("""(?i)\bADD COLUMN\b""").findAll(migration).count())
    }

    @Test
    fun `changing the bound or dropping the null alternative is caught`() {
        assertFalse(adds(migration.replace("BETWEEN 1 AND 64", "BETWEEN 1 AND 65")))
        assertFalse(adds(migration.replace("caller_service IS NULL OR ", "")))
    }

    @Test
    fun `no policy or grant is touched`() {
        assertFalse(Regex("""(?i)\b(POLICY|GRANT|REVOKE|ROW LEVEL)\b""").containsMatchIn(migration))
    }
}
