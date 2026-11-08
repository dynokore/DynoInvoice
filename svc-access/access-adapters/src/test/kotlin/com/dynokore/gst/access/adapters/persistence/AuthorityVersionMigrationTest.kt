package com.dynokore.gst.access.adapters.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The forward migration that stores the authority version with each record (INV-T-9061, INV-AC-9051). */
class AuthorityVersionMigrationTest {
    private val file = "V202611091000__add_authority_version.sql"

    private val migration: String =
        checkNotNull(
            javaClass.classLoader.getResourceAsStream("db/migration/$file"),
        ) { "$file is not on the classpath" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun adds(
        sql: String,
        table: String,
    ): Boolean =
        Regex(
            "ALTER TABLE access\\.$table\\s+ADD COLUMN authority_version bigint " +
                "CHECK \\(authority_version IS NULL OR authority_version >= 1\\);",
        ).containsMatchIn(sql)

    private fun policies(
        sql: String,
        verb: String,
    ) = Regex("""(?im)^$verb POLICY authority_version_backfill ON access\.(\w+)""")
        .findAll(sql)
        .map { it.groupValues[1] }
        .toList()
        .sorted()

    @Test
    fun `INV-AC-9051 the file adds the three nullable columns with a check of at least 1`() {
        assertTrue(migration.startsWith("-- ") && "SET lock_timeout = '5s';" in migration)
        listOf("role_grant", "consent", "service_principal").forEach { assertTrue(adds(migration, it), it) }
        assertEquals(3, Regex("""(?i)\bADD COLUMN\b""").findAll(migration).count())
    }

    @Test
    fun `INV-AC-9051 changing the bound or the null alternative is caught`() {
        assertFalse(adds(migration.replace(">= 1", ">= 0"), "role_grant"))
        assertFalse(adds(migration.replace("authority_version IS NULL OR ", ""), "consent"))
        assertFalse(adds(migration.replace("bigint", "integer"), "service_principal"))
    }

    @Test
    fun `INV-AC-9051 the backfill policy of each table is created and dropped and no forcing changes`() {
        val tables = listOf("consent", "role_grant", "service_principal")
        assertEquals(tables, policies(migration, "CREATE"))
        assertEquals(tables, policies(migration, "DROP"))
        val forcing = Regex("""(?i)\b(NO\s+)?FORCE\s+ROW\s+LEVEL\b|\bDISABLE\s+ROW\b|\bBYPASSRLS\b""")
        assertFalse(forcing.containsMatchIn(migration))
        // a policy left behind is caught
        val leftBehind = migration.replace("DROP POLICY authority_version_backfill ON access.service_principal;", "")
        assertEquals(listOf("consent", "role_grant"), policies(leftBehind, "DROP"))
    }

    @Test
    fun `INV-AC-9051 the backfill draws one sequence value per row in one pass ordered by updated_at and id`() {
        assertEquals(1, Regex("""nextval\('access\.authority_version_seq'\)""").findAll(migration).count())
        assertTrue(Regex("""ORDER BY updated_at, id""").containsMatchIn(migration))
        assertTrue("WHERE status IN ('ACTIVE', 'REVOKED')" in migration)
        assertTrue("WHERE status = 'REVOKED'" in migration)
    }
}
