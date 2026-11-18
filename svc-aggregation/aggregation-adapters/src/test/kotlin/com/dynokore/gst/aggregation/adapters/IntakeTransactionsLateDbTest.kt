package com.dynokore.gst.aggregation.adapters

import com.dynokore.gst.aggregation.adapters.events.AggregateReadyOutbox
import com.dynokore.gst.aggregation.adapters.events.ShortCodeUsedOutbox
import com.dynokore.gst.aggregation.adapters.events.WindowChangedOutbox
import com.dynokore.gst.aggregation.adapters.persistence.JooqAggregateStore
import com.dynokore.gst.aggregation.adapters.persistence.JooqTransactionStore
import com.dynokore.gst.aggregation.adapters.persistence.JooqUsedShortCodes
import com.dynokore.gst.aggregation.adapters.persistence.JooqWindowStateVersions
import com.dynokore.gst.aggregation.adapters.persistence.JooqWindowStore
import com.dynokore.gst.aggregation.application.CloseWindows
import com.dynokore.gst.aggregation.application.DatedTransaction
import com.dynokore.gst.aggregation.application.IntakeAggregate
import com.dynokore.gst.aggregation.application.IntakeTransactions
import com.dynokore.gst.aggregation.application.PeriodSpans
import com.dynokore.gst.aggregation.application.ShortCodes
import com.dynokore.gst.aggregation.application.TransactionsCommand
import com.dynokore.gst.aggregation.application.WindowChange
import com.dynokore.gst.aggregation.application.WindowChangePublisher
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.gst.aggregation.domain.PeriodSpan
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-24763 over the real database: `IntakeTransactions.execute` composed over the real window store, transaction
 * store, `WindowChangedOutbox` and `JooqWindowStateVersions` on one connection. The runner commits when the outermost
 * call returns and rolls back when it throws, as the transaction runner of a service does.
 */
class IntakeTransactionsLateDbTest {
    private val day = LocalDate.of(2027, 3, 10)
    private val open = LocalDate.of(2027, 3, 20)
    private val tick = LocalDate.of(2027, 3, 15)
    private val spans =
        PeriodSpans.Known(listOf(PeriodSpan(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 31), "2027-03")))

    private class Codes : ShortCodes {
        override fun entityCode(entityRef: String) = "E1"

        override fun businessUnitCode(businessUnitRef: String) = "B1"
    }

    private fun entry(
        type: String,
        name: String,
    ) = CatalogueEntry(type, name, setOf("aggregation-svc"))

    private inner class World(
        val tenant: UUID,
        private val connection: Connection,
    ) {
        private var depth = 0
        val runner =
            object : TransactionRunner {
                override fun <R> inTransaction(block: () -> R): R {
                    SignedBindings.bind(connection, tenant)
                    depth++
                    return try {
                        block().also { if (depth == 1) connection.commit() }
                    } catch (failure: Throwable) {
                        if (depth == 1) connection.rollback()
                        throw failure
                    } finally {
                        depth--
                    }
                }
            }
        private val tenants =
            object : TenantContext {
                override fun current() = TenantId(tenant)

                override fun currentActor(): String = SignedBindings.ACTOR

                override fun <R> bind(
                    tenant: TenantId,
                    actor: String,
                    block: () -> R,
                ): R = block()
            }
        private val dsl = DSL.using(connection, SQLDialect.POSTGRES)
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
                        entry(WindowChangedOutbox.TYPE, "gst.aggregation.aggregate-window-changed.v1"),
                    ),
                ),
                Clock.systemUTC(),
            )
        val windows = JooqWindowStore(dsl, runner, tenants)
        val publisher: WindowChangePublisher =
            WindowChangedOutbox(writer, tenants, JooqWindowStateVersions(dsl, runner))
        private val used = JooqUsedShortCodes(dsl, tenants, ShortCodeUsedOutbox(writer, tenants))
        private val aggregates = JooqAggregateStore(dsl, runner, tenants, used)
        private val transactionStore = JooqTransactionStore(dsl, runner, tenants)

        fun intake(publishing: WindowChangePublisher = publisher) =
            IntakeTransactions(
                { _, _, _ -> Cadence.DAILY },
                windows,
                transactionStore.scoped("BU-L", AggregateType.POS),
                { _, _, _ -> spans },
                publishing,
                runner,
            )

        fun send(
            entity: String,
            ref: String,
            date: LocalDate,
            publishing: WindowChangePublisher = publisher,
        ) = intake(publishing).execute(
            TransactionsCommand(
                entity,
                "BU-L",
                AggregateType.POS,
                "SK-$ref",
                1,
                listOf(DatedTransaction(ref, date, "SR-STD", "10.00")),
            ),
        )

        /** Stores a first transaction per entity, then runs the closing run that closes each window's part. */
        fun closeWindowsOf(vararg entities: String) {
            entities.forEach { send(it, "FIRST-$it", day) }
            runner.inTransaction {
                val intake = IntakeAggregate(Codes(), aggregates)
                CloseWindows(windows, { _, _, _ -> spans }, intake, publisher).execute(tick)
            }
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

    private fun lateRows(tenant: UUID) =
        rows(
            "select w.entity_ref || ':' || l.part_start || '..' || l.part_end from " +
                "aggregation.aggregate_window_part_late l join aggregation.aggregate_window w " +
                "on w.window_id = l.window_id where l.tenant_id = '$tenant' order by 1",
        )

    private fun events(tenant: UUID) =
        rows(
            "select (data->>'entityRef') || ':' || (data->>'state') || ':' || (data->>'stateVersion') || ':' || " +
                "coalesce(data->>'aggregateId', 'none') from aggregation.outbox " +
                "where tenant_id = '$tenant' and type = '${WindowChangedOutbox.TYPE}' order by seq",
        )

    private fun versions(tenant: UUID) =
        rows(
            "select entity_ref || ':' || state_version from aggregation.window_state_version " +
                "where tenant_id = '$tenant' order by 1",
        )

    private fun aggregateRows(tenant: UUID) =
        rows(
            "select row_to_json(a)::text || coalesce((select json_agg(l order by position)::text from " +
                "aggregation.aggregate_line l where l.aggregate_id = a.aggregate_id), '') || " +
                "coalesce((select json_agg(t order by transaction_ref)::text " +
                "from aggregation.aggregate_transaction t where t.tenant_id = a.tenant_id " +
                "and t.aggregate_id = a.aggregate_id), '') " +
                "from aggregation.aggregate a where a.tenant_id = '$tenant' order by a.entity_ref",
        )

    private fun assigned(
        tenant: UUID,
        ref: String,
    ) = rows(
        "select coalesce(aggregate_id::text, 'null') from aggregation.aggregate_transaction " +
            "where tenant_id = '$tenant' and transaction_ref = '$ref'",
    )

    @Test
    fun `INV-AC-24763 a transaction dated inside a closed part records it LATE once and publishes LATE once`() {
        val tenant = UUID.randomUUID()
        world(tenant) { w ->
            w.closeWindowsOf("E-1", "E-2")
            val closedAggregate =
                rows(
                    "select aggregate_id::text from aggregation.aggregate " +
                        "where tenant_id = '$tenant' and entity_ref = 'E-1'",
                ).single()
            events(tenant).map { it.substringBeforeLast(":") } shouldBe listOf("E-1:CLOSED:1", "E-2:CLOSED:1")
            events(tenant).first() shouldBe "E-1:CLOSED:1:$closedAggregate"
            val aggregatesBefore = aggregateRows(tenant)
            val versionsBefore = versions(tenant)
            versionsBefore shouldBe listOf("E-1:1", "E-2:1")

            w.send("E-1", "T-LATE", day)

            assigned(tenant, "T-LATE") shouldBe listOf("null")
            lateRows(tenant) shouldBe listOf("E-1:2027-03-10..2027-03-10")
            events(tenant).filter { it.startsWith("E-1:") } shouldBe
                listOf("E-1:CLOSED:1:$closedAggregate", "E-1:LATE:2:none")
            versions(tenant) shouldBe listOf("E-1:2", "E-2:1")
            aggregateRows(tenant) shouldBe aggregatesBefore
        }
    }

    @Test
    fun `INV-AC-24763 a second transaction in the same part adds no row, no event and no counter change`() {
        val tenant = UUID.randomUUID()
        world(tenant) { w ->
            w.closeWindowsOf("E-1")
            w.send("E-1", "T-LATE", day)
            val eventsBefore = events(tenant)
            val versionsBefore = versions(tenant)
            val aggregatesBefore = aggregateRows(tenant)

            w.send("E-1", "T-LATE2", day)

            assigned(tenant, "T-LATE2") shouldBe listOf("null")
            lateRows(tenant) shouldBe listOf("E-1:2027-03-10..2027-03-10")
            events(tenant) shouldBe eventsBefore
            versions(tenant) shouldBe versionsBefore
            aggregateRows(tenant) shouldBe aggregatesBefore
        }
    }

    @Test
    fun `INV-AC-24763 a transaction dated inside an open part records nothing and appends no event`() {
        val tenant = UUID.randomUUID()
        world(tenant) { w ->
            w.closeWindowsOf("E-1")
            val eventsBefore = events(tenant)
            val versionsBefore = versions(tenant)
            val aggregatesBefore = aggregateRows(tenant)

            w.send("E-1", "T-OPEN", open)

            assigned(tenant, "T-OPEN") shouldBe listOf("null")
            lateRows(tenant) shouldBe emptyList()
            events(tenant) shouldBe eventsBefore
            versions(tenant) shouldBe versionsBefore
            aggregateRows(tenant) shouldBe aggregatesBefore
        }
    }

    @Test
    fun `INV-AC-24763 the late transaction of another entity records only the part of that entity`() {
        val tenant = UUID.randomUUID()
        world(tenant) { w ->
            w.closeWindowsOf("E-1", "E-2")
            w.send("E-2", "T-E2", day)
            lateRows(tenant) shouldBe listOf("E-2:2027-03-10..2027-03-10")
            events(tenant).count { it.startsWith("E-1:") } shouldBe 1
            events(tenant).count { it.startsWith("E-2:LATE:2") } shouldBe 1
        }
    }

    @Test
    fun `INV-AC-24763 the LATE row and its event roll back together when the intake fails after the event`() {
        val tenant = UUID.randomUUID()
        world(tenant) { w ->
            w.closeWindowsOf("E-1")
            val eventsBefore = events(tenant)
            val versionsBefore = versions(tenant)
            val aggregatesBefore = aggregateRows(tenant)

            shouldThrow<IllegalStateException> {
                w.send(
                    "E-1",
                    "T-LATE",
                    day,
                    WindowChangePublisher { change: WindowChange ->
                        w.publisher.changed(change)
                        error("the intake fails after the event was appended")
                    },
                )
            }

            lateRows(tenant) shouldBe emptyList()
            events(tenant) shouldBe eventsBefore
            versions(tenant) shouldBe versionsBefore
            assigned(tenant, "T-LATE") shouldBe emptyList()
            aggregateRows(tenant) shouldBe aggregatesBefore
        }
    }
}
