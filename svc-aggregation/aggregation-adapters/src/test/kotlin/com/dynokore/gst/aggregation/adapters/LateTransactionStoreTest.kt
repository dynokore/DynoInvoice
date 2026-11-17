package com.dynokore.gst.aggregation.adapters

import com.dynokore.gst.aggregation.adapters.events.AggregateReadyOutbox
import com.dynokore.gst.aggregation.adapters.events.ShortCodeUsedOutbox
import com.dynokore.gst.aggregation.adapters.persistence.JooqAggregateStore
import com.dynokore.gst.aggregation.adapters.persistence.JooqTransactionStore
import com.dynokore.gst.aggregation.adapters.persistence.JooqUsedShortCodes
import com.dynokore.gst.aggregation.adapters.persistence.JooqWindowStore
import com.dynokore.gst.aggregation.application.CloseWindows
import com.dynokore.gst.aggregation.application.DatedTransaction
import com.dynokore.gst.aggregation.application.IntakeAggregate
import com.dynokore.gst.aggregation.application.PeriodSpans
import com.dynokore.gst.aggregation.application.PlacedTransaction
import com.dynokore.gst.aggregation.application.Placement
import com.dynokore.gst.aggregation.application.ShortCodes
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.AggregationWindow
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.gst.aggregation.domain.PeriodSpan
import com.dynokore.gst.aggregation.domain.WindowState
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-24210 over the real database: the closing run assigns the late transactions of a transmitted closed part to
 * supplementary aggregates and never writes the transmitted aggregate (INV-REQ-439, INV-REQ-438).
 */
class LateTransactionStoreTest {
    private val day = LocalDate.of(2027, 3, 10)
    private val dayQ = LocalDate.of(2027, 3, 11)
    private val tick = LocalDate.of(2027, 3, 15)
    private val march = PeriodSpan(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 31), "2027-03")
    private val spans = PeriodSpans.Known(listOf(march))

    private class Codes : ShortCodes {
        override fun entityCode(entityRef: String) = "E1"

        override fun businessUnitCode(businessUnitRef: String) = "B1"
    }

    private fun entry(
        type: String,
        name: String,
    ) = CatalogueEntry(type, name, setOf("aggregation-svc"))

    /** One tenant's stores over a runtime connection, and the closing run built the way the job builds it. */
    private inner class World(
        val tenant: UUID,
        connection: java.sql.Connection,
    ) {
        val runner =
            object : TransactionRunner {
                override fun <R> inTransaction(block: () -> R): R {
                    SignedBindings.bind(connection, tenant)
                    return try {
                        block().also { connection.commit() }
                    } catch (failure: Throwable) {
                        connection.rollback()
                        throw failure
                    }
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
        private val writer =
            JdbcOutboxWriter(
                dsl,
                "aggregation",
                "aggregation-svc",
                "aggregation-svc-0",
                EventCatalogue(
                    listOf(
                        entry(AggregateReadyOutbox.TYPE, "gst.aggregation.aggregate-ready.v1"),
                        entry(ShortCodeUsedOutbox.TYPE, "gst.aggregation.short-code-used.v1"),
                    ),
                ),
                Clock.systemUTC(),
            )
        val windows = JooqWindowStore(dsl, runner, tenants)
        private val used = JooqUsedShortCodes(dsl, tenants, ShortCodeUsedOutbox(writer, tenants))
        private val aggregates = JooqAggregateStore(dsl, runner, tenants, used)
        private val transactions = JooqTransactionStore(dsl, runner, tenants).scoped("BU-L", AggregateType.POS)
        val close =
            CloseWindows(windows, { _, _, _ -> spans }, IntakeAggregate(Codes(), aggregates), { })

        @Suppress("LongParameterList")
        fun store(
            entity: String,
            ref: String,
            date: LocalDate,
            amount: String,
            credit: Boolean,
            state: WindowState,
        ) {
            val placement = Placement(AggregationWindow.containing(Cadence.DAILY, date), state)
            // the window row is opened by the placement of the transaction's own date
            windows.place(entity, "BU-L", AggregateType.POS, date, Cadence.DAILY)
            transactions.store(
                entity,
                "SK-$ref",
                1,
                listOf(
                    PlacedTransaction(
                        DatedTransaction(ref, date, "SR-STD", amount, isCreditNote = credit),
                        placement,
                    ),
                ),
            )
        }
    }

    private fun <T> world(
        tenant: UUID,
        block: (World) -> T,
    ): T =
        AggregationDb.connect(AggregationDb.RUNTIME, AggregationDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            block(World(tenant, connection))
        }

    private fun rows(sql: String): List<String> =
        AggregationDb.superuser().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { r -> generateSequence { if (r.next()) r.getString(1) else null }.toList() }
            }
        }

    private fun exec(sql: String) =
        AggregationDb.superuser().use { c ->
            c.autoCommit = true
            c.createStatement().use { it.executeUpdate(sql) }
        }

    private fun transmit(tenant: UUID) =
        exec("update aggregation.aggregate set transmitted_at = now() where tenant_id = '$tenant'")

    private fun idOfKey(
        tenant: UUID,
        key: String,
    ) = rows("select aggregate_id::text from aggregation.aggregate where tenant_id = '$tenant' and source_key = '$key'")
        .single()

    private fun aggregateOf(
        tenant: UUID,
        ref: String,
    ) = rows(
        "select aggregate_id::text from aggregation.aggregate_transaction " +
            "where tenant_id = '$tenant' and transaction_ref = '$ref'",
    ).single()

    private fun unassigned(
        tenant: UUID,
        where: String,
    ) = rows(
        "select count(*)::text from aggregation.aggregate_transaction " +
            "where tenant_id = '$tenant' and aggregate_id is null and $where",
    ).single().toInt()

    private fun aggregateCount(tenant: UUID) =
        rows("select count(*)::text from aggregation.aggregate where tenant_id = '$tenant'").single().toInt()

    private fun flags(id: String) =
        rows(
            "select supplementary::text || ',' || credit_note::text from aggregation.aggregate " +
                "where aggregate_id = '$id'",
        )

    private fun lineAmounts(id: String) =
        rows("select amount from aggregation.aggregate_line where aggregate_id = '$id'")

    private fun sequenceOf(id: String) =
        rows("select sequence::text from aggregation.aggregate where aggregate_id = '$id'").single().toLong()

    private fun documents(vararg ids: String) =
        rows(
            "select count(distinct document_number)::text from aggregation.aggregate " +
                "where aggregate_id in (${ids.joinToString { "'$it'" }})",
        ).single().toInt()

    /** The row of the aggregate, its lines and its transaction rows, as text, to compare before and after. */
    private fun snapshot(
        tenant: UUID,
        id: String,
    ) = rows(
        "select (select row_to_json(a)::text from aggregation.aggregate a where a.aggregate_id = '$id') || " +
            "coalesce((select json_agg(l order by position)::text from aggregation.aggregate_line l " +
            "where l.aggregate_id = '$id'), '') || " +
            "coalesce((select json_agg(t order by transaction_ref)::text from aggregation.aggregate_transaction t " +
            "where t.tenant_id = '$tenant' and t.aggregate_id = '$id'), '')",
    ).single()

    @Test
    fun `INV-AC-24210 a late sales and a late credit note of a transmitted part get one supplementary each`() {
        val tenant = UUID.randomUUID()
        val entity = "E-L1"
        world(tenant) { w ->
            w.store(entity, "S0", day, "100.00", false, WindowState.OPEN)
            w.close.execute(tick)
            val a = idOfKey(tenant, "window:$entity:POS:$day:$day")
            transmit(tenant)
            w.store(entity, "L-S", day, "7.50", false, WindowState.CLOSED)
            w.store(entity, "L-C", day, "-2.25", true, WindowState.CLOSED)
            aggregateOf(tenant, "S0") shouldBe a
            val before = snapshot(tenant, a)
            val count = aggregateCount(tenant)

            w.close.execute(tick.plusDays(1))

            aggregateCount(tenant) shouldBe count + 2
            val sales = aggregateOf(tenant, "L-S")
            val credit = aggregateOf(tenant, "L-C")
            setOf(a, sales, credit).size shouldBe 3
            sales shouldBe idOfKey(tenant, "window-late:$entity:POS:$day:$day:1")
            credit shouldBe idOfKey(tenant, "window-late-credit:$entity:POS:$day:$day:1")
            flags(sales) shouldBe listOf("true,false")
            flags(credit) shouldBe listOf("true,true")
            lineAmounts(sales) shouldBe listOf("7.50")
            lineAmounts(credit) shouldBe listOf("-2.25")
            documents(a, sales, credit) shouldBe 3
            (sequenceOf(sales) > sequenceOf(a) && sequenceOf(credit) > sequenceOf(a)) shouldBe true
            snapshot(tenant, a) shouldBe before
        }
    }

    @Test
    fun `INV-AC-24210 the second run creates nothing`() {
        val tenant = UUID.randomUUID()
        val entity = "E-L2"
        world(tenant) { w ->
            w.store(entity, "S0", day, "100.00", false, WindowState.OPEN)
            w.close.execute(tick)
            transmit(tenant)
            w.store(entity, "L-S", day, "7.50", false, WindowState.CLOSED)
            w.close.execute(tick)
            val count = aggregateCount(tenant)
            val assigned = aggregateOf(tenant, "L-S")

            w.close.execute(tick)

            aggregateCount(tenant) shouldBe count
            aggregateOf(tenant, "L-S") shouldBe assigned
        }
    }

    @Test
    fun `INV-AC-24210 a part not yet transmitted keeps its late transaction unassigned until the mark is set`() {
        val tenant = UUID.randomUUID()
        val entity = "E-L3"
        world(tenant) { w ->
            w.store(entity, "Q0", dayQ, "50.00", false, WindowState.OPEN)
            w.close.execute(tick)
            w.store(entity, "L-Q", dayQ, "3.00", false, WindowState.CLOSED)
            val count = aggregateCount(tenant)

            w.close.execute(tick)

            aggregateCount(tenant) shouldBe count
            unassigned(tenant, "transaction_ref = 'L-Q'") shouldBe 1

            transmit(tenant)
            w.close.execute(tick)

            aggregateCount(tenant) shouldBe count + 1
            aggregateOf(tenant, "L-Q") shouldBe idOfKey(tenant, "window-late:$entity:POS:$dayQ:$dayQ:1")
        }
    }

    @Test
    fun `INV-AC-24210 a later late transaction gets the next key and no transaction is in two aggregates`() {
        val tenant = UUID.randomUUID()
        val entity = "E-L4"
        world(tenant) { w ->
            w.store(entity, "S0", day, "100.00", false, WindowState.OPEN)
            w.close.execute(tick)
            transmit(tenant)
            w.store(entity, "L-1", day, "1.00", false, WindowState.CLOSED)
            w.close.execute(tick)
            w.store(entity, "L-2", day, "2.00", false, WindowState.CLOSED)
            w.close.execute(tick)

            aggregateOf(tenant, "L-1") shouldBe idOfKey(tenant, "window-late:$entity:POS:$day:$day:1")
            aggregateOf(tenant, "L-2") shouldBe idOfKey(tenant, "window-late:$entity:POS:$day:$day:2")
            unassigned(tenant, "true") shouldBe 0
        }
    }

    @Test
    fun `INV-AC-24210 a late transaction of another tenant is in no aggregate of this tenant`() {
        val mine = UUID.randomUUID()
        val other = UUID.randomUUID()
        val entity = "E-L5"
        world(other) { w ->
            w.store(entity, "S0", day, "100.00", false, WindowState.OPEN)
            w.close.execute(tick)
            transmit(other)
            w.store(entity, "L-OTHER", day, "999.00", false, WindowState.CLOSED)
        }
        world(mine) { w ->
            w.store(entity, "S0", day, "100.00", false, WindowState.OPEN)
            w.close.execute(tick)
            transmit(mine)
            w.store(entity, "L-MINE", day, "1.00", false, WindowState.CLOSED)

            w.close.execute(tick)

            lineAmounts(aggregateOf(mine, "L-MINE")) shouldBe listOf("1.00")
            unassigned(other, "transaction_ref = 'L-OTHER'") shouldBe 1
            rows(
                "select count(*)::text from aggregation.aggregate " +
                    "where tenant_id = '$other' and source_key like 'window-late%'",
            ) shouldBe listOf("0")
        }
    }
}
