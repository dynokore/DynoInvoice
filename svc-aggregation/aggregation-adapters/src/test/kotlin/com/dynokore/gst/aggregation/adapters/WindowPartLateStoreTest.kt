package com.dynokore.gst.aggregation.adapters

import com.dynokore.gst.aggregation.adapters.persistence.JooqWindowStore
import com.dynokore.gst.aggregation.application.OpenWindow
import com.dynokore.gst.aggregation.domain.AggregateType
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

/** `JooqWindowStore.recordLate` and the table of V202611181100 on a real database (INV-AC-24763). */
class WindowPartLateStoreTest {
    private fun d(
        m: Int,
        day: Int,
    ) = LocalDate.of(2027, m, day)

    private val closedPart = WindowPart(d(3, 28), d(3, 31), "2027-03")
    private val openPart = WindowPart(d(4, 1), d(4, 3), "2027-04")

    private fun lateRows(tenant: UUID): List<String> =
        AggregationDb.superuser().use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "select w.entity_ref || ':' || l.part_start from aggregation.aggregate_window_part_late l " +
                            "join aggregation.aggregate_window w on w.window_id = l.window_id " +
                            "where l.tenant_id = '$tenant' order by 1",
                    ).use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
            }
        }

    private fun query(sql: String): List<String> =
        AggregationDb.superuser().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                }
            }
        }

    private fun <T> withStore(
        tenant: UUID,
        block: (JooqWindowStore, (String) -> UUID) -> T,
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
            val aggregate = { entity: String ->
                val id = UUID.randomUUID()
                runner.inTransaction {
                    dsl.execute(
                        "insert into aggregation.aggregate (aggregate_id, tenant_id, entity_ref, business_unit_ref, " +
                            "aggregate_type, period_start, period_end, sequence, source_key, source_revision, " +
                            "entity_code, business_unit_code, document_number, issue_date, transaction_date) " +
                            "values (?, ?, ?, 'BU-L', 'POS', date '2027-03-28', date '2027-03-31', " +
                            "nextval('aggregation.aggregate_sequence'), ?, 1, 'E', 'B', ?, date '2027-03-31', " +
                            "date '2027-03-31')",
                        id,
                        tenant,
                        entity,
                        "L-$id",
                        "DOC-$id",
                    )
                }
                id
            }
            block(JooqWindowStore(dsl, runner, tenants), aggregate)
        }

    private fun place(
        store: JooqWindowStore,
        entity: String,
    ): OpenWindow {
        val placed = store.place(entity, "BU-L", AggregateType.POS, d(3, 31), Cadence.WEEKLY)
        return OpenWindow(entity, "BU-L", AggregateType.POS, placed.window)
    }

    @Test
    fun `INV-AC-24763 the first recordLate of a closed part returns true and leaves one row`() {
        val tenant = UUID.randomUUID()
        withStore(tenant) { store, aggregate ->
            val window = place(store, "E-L1")
            store.closePart(window, closedPart, aggregate("E-L1"))
            store.recordLate(window, closedPart) shouldBe true
        }
        lateRows(tenant) shouldBe listOf("E-L1:2027-03-28")
    }

    @Test
    fun `INV-AC-24763 a second recordLate of the same part returns false and leaves one row`() {
        val tenant = UUID.randomUUID()
        withStore(tenant) { store, aggregate ->
            val window = place(store, "E-L1")
            store.closePart(window, closedPart, aggregate("E-L1"))
            store.recordLate(window, closedPart) shouldBe true
            store.recordLate(window, closedPart) shouldBe false
        }
        lateRows(tenant) shouldBe listOf("E-L1:2027-03-28")
    }

    @Test
    fun `INV-AC-24763 recordLate of an open part of the same window is refused and writes nothing`() {
        val tenant = UUID.randomUUID()
        withStore(tenant) { store, aggregate ->
            val window = place(store, "E-L1")
            store.closePart(window, closedPart, aggregate("E-L1"))
            val refused = shouldThrow<IllegalStateException> { store.recordLate(window, openPart) }
            refused.message!! shouldContain "closed"
        }
        lateRows(tenant) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24763 the part of another entity is not touched`() {
        val tenant = UUID.randomUUID()
        withStore(tenant) { store, aggregate ->
            val first = place(store, "E-L1")
            val second = place(store, "E-L2")
            store.closePart(first, closedPart, aggregate("E-L1"))
            store.closePart(second, closedPart, aggregate("E-L2"))
            store.recordLate(first, closedPart) shouldBe true
        }
        lateRows(tenant) shouldBe listOf("E-L1:2027-03-28")
    }

    @Test
    fun `INV-AC-24763 the late table is under forced row level security with a tenant policy`() {
        query(
            "select relrowsecurity::text || relforcerowsecurity::text from pg_class " +
                "where oid = 'aggregation.aggregate_window_part_late'::regclass",
        ) shouldBe listOf("truetrue")
        query(
            "select polname from pg_policy where polrelid = 'aggregation.aggregate_window_part_late'::regclass " +
                "order by 1",
        ) shouldBe listOf("aggregate_window_part_late_tenant_isolation")
        query(
            "select string_agg(a.attname, ',' order by a.attnum) from pg_index i join pg_attribute a " +
                "on a.attrelid = i.indrelid and a.attnum = any (i.indkey) " +
                "where i.indrelid = 'aggregation.aggregate_window_part_late'::regclass and i.indisprimary",
        ) shouldBe listOf("tenant_id,window_id,part_start")
    }

    @Test
    fun `INV-AC-24763 the late migration version is above V202611171000`() {
        query(
            "select (version::bigint > 202611171000)::text from aggregation.flyway_schema_history " +
                "where success and description = 'window part late'",
        ) shouldBe listOf("true")
    }
}
