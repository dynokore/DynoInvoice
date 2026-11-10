package com.dynokore.gst.aggregation.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The migration V3__aggregation_windows.sql as the database holds it (CS-19). */
class WindowStoreTest {
    private fun query(sql: String): List<String> =
        AggregationDb.superuser().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                }
            }
        }

    @Test
    fun `the window table is tenant data under forced row level security with a bound_tenant policy`() {
        query(
            "select relrowsecurity::text || relforcerowsecurity::text from pg_class " +
                "where oid = 'aggregation.aggregate_window'::regclass",
        ) shouldBe listOf("truetrue")
        query("select polname from pg_policy where polrelid = 'aggregation.aggregate_window'::regclass") shouldBe
            listOf("aggregate_window_tenant_isolation")
    }

    @Test
    fun `the runtime role may only select, insert and update the state of a window`() {
        query(
            "select privilege_type from information_schema.role_table_grants where grantee = 'aggregation_runtime' " +
                "and table_name = 'aggregate_window' order by 1",
        ) shouldBe listOf("INSERT", "SELECT")
        query(
            "select column_name from information_schema.column_privileges where grantee = 'aggregation_runtime' " +
                "and table_name = 'aggregate_window' and privilege_type = 'UPDATE'",
        ) shouldBe listOf("state")
    }

    @Test
    fun `the aggregate table holds the placement of the first intake`() {
        query(
            "select column_name from information_schema.columns where table_schema = 'aggregation' " +
                "and table_name = 'aggregate' and column_name like 'window\\_%' order by 1",
        ) shouldBe listOf("window_end", "window_start", "window_state")
    }
}
