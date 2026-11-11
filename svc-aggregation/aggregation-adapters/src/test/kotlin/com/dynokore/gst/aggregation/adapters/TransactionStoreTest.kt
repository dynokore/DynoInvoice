package com.dynokore.gst.aggregation.adapters

import com.dynokore.gst.aggregation.adapters.periods.NoPeriodBoundaries
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID
import com.dynokore.gst.aggregation.application.PeriodSpans as Spans

/** The migration V6__aggregate_transactions.sql as the database holds it (CS-19, INV-REQ-434, INV-REQ-435). */
class TransactionStoreTest {
    private fun query(sql: String): List<String> =
        AggregationDb.superuser().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                }
            }
        }

    private fun runtime(block: (Connection) -> Unit) =
        AggregationDb.connect(AggregationDb.RUNTIME, AggregationDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            block(connection)
        }

    private fun insert(
        connection: Connection,
        tenant: UUID,
        ref: String,
        date: String = "2022-03-31",
    ) = connection
        .prepareStatement(
            "insert into aggregation.aggregate_transaction (tenant_id, entity_ref, business_unit_ref, " +
                "aggregate_type, source_key, source_revision, transaction_ref, position, transaction_date, " +
                "internal_tax_code, amount, window_start, window_end, window_state) values (?, 'E-001', 'BU-01', " +
                "'PCP', 'K-1', 1, ?, 0, date '$date', 'SR-STD', '10.00', date '2022-03-28', date '2022-04-03', 'OPEN')",
        ).use {
            it.setObject(1, tenant)
            it.setString(2, ref)
            it.executeUpdate()
        }

    @Test
    fun `both new tables are tenant data under forced row level security with a bound_tenant policy`() {
        listOf("aggregate_transaction", "aggregate_window_part").forEach { table ->
            query(
                "select relrowsecurity::text || relforcerowsecurity::text from pg_class " +
                    "where oid = 'aggregation.$table'::regclass",
            ) shouldBe listOf("truetrue")
            query(
                "select count(*)::text from pg_policy where polrelid = 'aggregation.$table'::regclass " +
                    "and pg_get_expr(polqual, polrelid) like '%bound_tenant()%' " +
                    "and pg_get_expr(polwithcheck, polrelid) like '%bound_tenant()%'",
            ) shouldBe listOf("1")
        }
    }

    @Test
    fun `the runtime role may select and insert transactions and update only the aggregate they went to`() {
        query(
            "select privilege_type from information_schema.role_table_grants where grantee = 'aggregation_runtime' " +
                "and table_name = 'aggregate_transaction' order by 1",
        ) shouldBe listOf("INSERT", "SELECT")
        query(
            "select column_name from information_schema.column_privileges where grantee = 'aggregation_runtime' " +
                "and table_name = 'aggregate_transaction' and privilege_type = 'UPDATE'",
        ) shouldBe listOf("aggregate_id")
        query(
            "select privilege_type from information_schema.role_table_grants where grantee = 'aggregation_runtime' " +
                "and table_name = 'aggregate_window_part' order by 1",
        ) shouldBe listOf("INSERT", "SELECT")
    }

    @Test
    fun `a transaction is unique on tenant, entity, source key, source revision and transaction reference`() {
        query(
            "select string_agg(a.attname, ',' order by a.attnum) from pg_constraint c " +
                "join pg_attribute a on a.attrelid = c.conrelid and a.attnum = any (c.conkey) " +
                "where c.conname = 'aggregate_transaction_key' and c.contype = 'u'",
        ) shouldBe listOf("tenant_id,entity_ref,source_key,source_revision,transaction_ref")
        val tenant = UUID.randomUUID()
        runtime { connection ->
            SignedBindings.bind(connection, tenant)
            insert(connection, tenant, "T-1")
            shouldThrow<SQLException> { insert(connection, tenant, "T-1") }.sqlState shouldBe "23505"
        }
    }

    @Test
    fun `a transaction dated outside the window it is stored with is refused by the database`() {
        val tenant = UUID.randomUUID()
        runtime { connection ->
            SignedBindings.bind(connection, tenant)
            val refused = shouldThrow<SQLException> { insert(connection, tenant, "T-1", date = "2022-04-04") }
            refused.sqlState shouldBe "23514"
        }
    }

    @Test
    fun `a session of another tenant reads no transaction`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        runtime { connection ->
            SignedBindings.bind(connection, a)
            insert(connection, a, "T-1")
            connection.commit()
        }
        runtime { connection ->
            SignedBindings.bind(connection, b)
            connection.createStatement().use { statement ->
                statement.executeQuery("select count(*) from aggregation.aggregate_transaction").use { rows ->
                    rows.next()
                    rows.getInt(1) shouldBe 0
                }
            }
        }
    }

    @Test
    fun `NoPeriodBoundaries knows no span for any entity or dates`() {
        val source = NoPeriodBoundaries()
        listOf("E-001", "E-002", "").forEach { entity ->
            source.spans(entity, LocalDate.of(2022, 1, 1), LocalDate.of(2022, 12, 31)) shouldBe Spans.Unknown
            source.spans(entity, LocalDate.of(2022, 3, 28), LocalDate.of(2022, 4, 3)) shouldBe Spans.Unknown
        }
    }
}
