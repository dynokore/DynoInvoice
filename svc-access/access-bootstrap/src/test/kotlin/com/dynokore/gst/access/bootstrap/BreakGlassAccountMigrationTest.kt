package com.dynokore.gst.access.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection

/** INV-T-8853 migration `V202611021200__create_break_glass_account.sql`: the table, its RLS, its grants and checks. */
class BreakGlassAccountMigrationTest {
    private fun scalar(
        c: Connection,
        sql: String,
    ): String? =
        c.createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }

    @Test
    fun `INV-AC-8899 the account table has forced row-level security and no delete grant`() {
        BoundaryFixture.asSuperuser { c ->
            val flags =
                scalar(
                    c,
                    "select relrowsecurity || ',' || relforcerowsecurity from pg_class " +
                        "where oid = 'access.break_glass_account'::regclass",
                )
            assertThat(flags).isEqualTo("true,true")
            val policy =
                scalar(
                    c,
                    "select polname || '|' || pg_get_expr(polqual, polrelid) || '|' || " +
                        "pg_get_expr(polwithcheck, polrelid) from pg_policy " +
                        "where polrelid = 'access.break_glass_account'::regclass",
                )!!
            assertThat(policy).startsWith("tenant_isolation|").contains("tenant_id", "bound_tenant")

            fun has(privilege: String) =
                scalar(c, "select has_table_privilege('access_runtime', 'access.break_glass_account', '$privilege')")
            assertThat(listOf("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE").map { has(it) })
                .containsExactly("t", "t", "t", "f", "f")
            // one row per tenant, and only a 64-character lower-case hex verifier
            val constraints = "from pg_constraint where conrelid = 'access.break_glass_account'::regclass"
            assertThat(scalar(c, "select count(*) $constraints and contype = 'u'")).isEqualTo("1")
            assertThat(
                scalar(
                    c,
                    "select pg_get_constraintdef(oid) $constraints and contype = 'c' " +
                        "and pg_get_constraintdef(oid) like '%verifier%'",
                ),
            ).contains("[0-9a-f]{64}")
        }
    }
}
