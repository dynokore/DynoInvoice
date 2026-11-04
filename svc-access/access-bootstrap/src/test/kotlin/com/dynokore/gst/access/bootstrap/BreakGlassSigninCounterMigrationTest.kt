package com.dynokore.gst.access.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection

/** INV-T-13465 migration `V202611031000__create_break_glass_signin_counter.sql`: the table, key, RLS and grants. */
class BreakGlassSigninCounterMigrationTest {
    private fun scalar(
        c: Connection,
        sql: String,
    ): String? =
        c.createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }

    @Test
    fun `INV-AC-9184 the counter table has its primary key, forced RLS and no delete grant`() {
        BoundaryFixture.asSuperuser { c ->
            val table = "'access.break_glass_signin_counter'::regclass"
            assertThat(scalar(c, "select to_regclass('access.break_glass_signin_counter')::text"))
                .isEqualTo("access.break_glass_signin_counter")
            val flags = "select relrowsecurity || ',' || relforcerowsecurity from pg_class where oid = $table"
            assertThat(scalar(c, flags)).isEqualTo("true,true")
            val key = "select pg_get_constraintdef(oid) from pg_constraint where conrelid = $table and contype = 'p'"
            assertThat(scalar(c, key)).isEqualTo("PRIMARY KEY (tenant_id, unlock_ref)")

            fun has(privilege: String) =
                scalar(
                    c,
                    "select has_table_privilege('access_runtime', 'access.break_glass_signin_counter', '$privilege')",
                )
            assertThat(listOf("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE").map { has(it) })
                .containsExactly("t", "t", "t", "f", "f")
        }
    }
}
