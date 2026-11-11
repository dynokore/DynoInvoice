package com.dynokore.gst.aggregation.adapters

import com.dynokore.gst.aggregation.adapters.persistence.JooqWindowStore
import com.dynokore.gst.aggregation.application.OpenWindow
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.AggregationWindow
import com.dynokore.gst.aggregation.domain.WindowPart
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

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

    private inline fun <reified T : Any> unused(): T =
        java.lang.reflect.Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ ->
            error("not called")
        } as T

    private val store =
        JooqWindowStore(DSL.using(SQLDialect.POSTGRES), unused<TransactionRunner>(), unused<TenantContext>())
    private val day = LocalDate.of(2027, 3, 31)
    private val window = OpenWindow("E-001", "BU-01", AggregateType.POS, AggregationWindow(day, day))

    private fun failsClosed(call: () -> Unit) {
        val failure = shouldThrow<UnsupportedOperationException> { call() }
        failure.javaClass shouldBe UnsupportedOperationException::class.java
        failure.message!! shouldContain "INV-T-14464"
    }

    @Test
    fun `isPartClosed fails closed with the INV-T-14464 token`() =
        failsClosed { store.isPartClosed(window, WindowPart(day, day, "P")) }

    @Test
    fun `closePart fails closed with the INV-T-14464 token`() =
        failsClosed { store.closePart(window, WindowPart(day, day, "P"), UUID.randomUUID()) }

    @Test
    fun `recordLate fails closed with the INV-T-14464 token`() = failsClosed { store.recordLate(window) }
}
