package com.dynokore.gst.aggregation.adapters

import com.dynokore.gst.aggregation.adapters.persistence.JooqWindowStore
import com.dynokore.gst.aggregation.application.OpenWindow
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.AggregationWindow
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.gst.aggregation.domain.WindowPart
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
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
        // The second policy is the select policy of aggregation_sys that open_window_entities() needs (V8).
        query(
            "select polname from pg_policy where polrelid = 'aggregation.aggregate_window'::regclass order by 1",
        ) shouldBe listOf("aggregate_window_sys_select", "aggregate_window_tenant_isolation")
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
    fun `recordLate fails closed with the INV-T-14464 token`() = failsClosed { store.recordLate(window) }

    private fun d(
        m: Int,
        day: Int,
    ) = LocalDate.of(2022, m, day)

    /** A window store and an aggregate inserter over one runtime-role connection bound to [tenant]. */
    private fun <T> withWindowStore(
        tenant: UUID,
        block: (JooqWindowStore, () -> UUID) -> T,
    ): T =
        AggregationDb.connect(AggregationDb.RUNTIME, AggregationDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            val runner =
                object : TransactionRunner {
                    override fun <R> inTransaction(block: () -> R): R {
                        SignedBindings.bind(connection, tenant)
                        return block().also { connection.commit() }
                    }
                }
            val tenants =
                object : TenantContext {
                    override fun current() = TenantId(tenant)

                    override fun currentActor(): String = SignedBindings.ACTOR

                    override fun <R> bind(
                        tenant: TenantId,
                        actor: String,
                        block: () -> R,
                    ): R = block()
                }
            val dsl = DSL.using(connection, SQLDialect.POSTGRES)
            var n = 0
            val aggregate = {
                val id = UUID.randomUUID()
                n++
                runner.inTransaction {
                    dsl.execute(
                        "insert into aggregation.aggregate (aggregate_id, tenant_id, entity_ref, business_unit_ref, " +
                            "aggregate_type, period_start, period_end, sequence, source_key, source_revision, " +
                            "entity_code, business_unit_code, document_number, issue_date, transaction_date) " +
                            "values (?, ?, 'E-W', 'BU-W', 'POS', date '2022-03-28', date '2022-03-31', " +
                            "nextval('aggregation.aggregate_sequence'), ?, 1, 'E', 'B', ?, date '2022-03-31', " +
                            "date '2022-03-31')",
                        id,
                        tenant,
                        "W-$id-$n",
                        "DOC-$id",
                    )
                }
                id
            }
            block(JooqWindowStore(dsl, runner, tenants), aggregate)
        }

    private fun place(store: JooqWindowStore): OpenWindow {
        val placed = store.place("E-W", "BU-W", AggregateType.POS, d(3, 31), Cadence.WEEKLY)
        return OpenWindow("E-W", "BU-W", AggregateType.POS, placed.window)
    }

    @Test
    fun `INV-AC-20428 the store lists a weekly window from its start, not before it`() {
        withWindowStore(UUID.randomUUID()) { store, _ ->
            val open = place(store)
            open.window shouldBe AggregationWindow(d(3, 28), d(4, 3))
            store.openWindowsStartingOnOrBefore(d(3, 31)) shouldBe listOf(open)
            store.openWindowsStartingOnOrBefore(d(3, 27)) shouldBe emptyList()
        }
    }

    @Test
    fun `INV-AC-20428 a window stays listed until its closed parts cover all its days`() {
        withWindowStore(UUID.randomUUID()) { store, aggregate ->
            val open = place(store)
            store.closePart(open, WindowPart(d(3, 28), d(3, 31), "2022-03"), aggregate())
            store.openWindowsStartingOnOrBefore(d(4, 3)) shouldBe listOf(open)
            store.closePart(open, WindowPart(d(4, 1), d(4, 3), "2022-04"), aggregate())
            store.openWindowsStartingOnOrBefore(d(4, 3)) shouldBe emptyList()
        }
    }

    @Test
    fun `INV-AC-20428 a window of another tenant is never listed`() {
        val other = UUID.randomUUID()
        withWindowStore(other) { store, _ -> place(store) }
        withWindowStore(UUID.randomUUID()) { store, _ ->
            store.openWindowsStartingOnOrBefore(d(4, 3)) shouldBe emptyList()
        }
    }
}
