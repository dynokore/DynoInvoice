package com.dynokore.gst.aggregation.adapters

import com.dynokore.gst.aggregation.adapters.events.AggregateReadyOutbox
import com.dynokore.gst.aggregation.adapters.events.ShortCodeUsedOutbox
import com.dynokore.gst.aggregation.adapters.persistence.JooqAggregateStore
import com.dynokore.gst.aggregation.adapters.persistence.JooqUsedShortCodes
import com.dynokore.gst.aggregation.adapters.persistence.storeLinesOf
import com.dynokore.gst.aggregation.application.NewAggregate
import com.dynokore.gst.aggregation.application.StoredAggregate
import com.dynokore.gst.aggregation.domain.AggregateLine
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** The taxable amount of a PCP transaction and line (INV-REQ-437), over the Testcontainers database. */
class PcpTaxableAmountTest {
    private val mapper = ObjectMapper()

    /** One transaction row: code, claimable input tax, taxable amount (null = not stated). */
    private data class Tx(
        val code: String,
        val claimable: String?,
        val taxable: String?,
    )

    private fun <T> withStore(
        tenant: UUID,
        block: (JooqAggregateStore, DSLContext, AggregateReadyOutbox, TransactionRunner) -> T,
    ): T =
        AggregationDb.connect(AggregationDb.RUNTIME, AggregationDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
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
            val catalogue =
                EventCatalogue(
                    listOf(
                        CatalogueEntry(
                            AggregateReadyOutbox.TYPE,
                            "gst.aggregation.aggregate-ready.v1",
                            setOf("aggregation-svc"),
                        ),
                        CatalogueEntry(
                            ShortCodeUsedOutbox.TYPE,
                            "gst.aggregation.short-code-used.v1",
                            setOf("aggregation-svc"),
                        ),
                    ),
                )
            val writer =
                JdbcOutboxWriter(
                    dsl,
                    "aggregation",
                    "aggregation-svc",
                    "aggregation-svc-0",
                    catalogue,
                    Clock.systemUTC(),
                )
            val used = JooqUsedShortCodes(dsl, tenants, ShortCodeUsedOutbox(writer, tenants))
            block(JooqAggregateStore(dsl, runner, tenants, used), dsl, AggregateReadyOutbox(writer, tenants), runner)
        }

    private fun <T> superuser(block: (java.sql.Connection) -> T): T = AggregationDb.superuser().use(block)

    private fun rows(sql: String): List<List<String?>> =
        superuser { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { r ->
                    generateSequence {
                        if (r.next()) (1..r.metaData.columnCount).map { r.getString(it) } else null
                    }.toList()
                }
            }
        }

    private fun newAggregate(
        type: AggregateType,
        key: String,
        lines: List<AggregateLine> = emptyList(),
    ) = NewAggregate(
        entityRef = "E-001",
        businessUnitRef = "BU-01",
        aggregateType = type,
        periodStart = LocalDate.of(2027, 3, 1),
        periodEnd = LocalDate.of(2027, 3, 31),
        sourceKey = key,
        sourceRevision = 1,
        sequence = 1,
        entityCode = "E1",
        businessUnitCode = "B1",
        documentNumber = "AGG-E1-${type.name}-B1-20270331-$key",
        issueDate = LocalDate.of(2027, 3, 31),
        transactionDate = LocalDate.of(2027, 3, 31),
        lines = lines,
    )

    /** Stores an aggregate, writes [txs] as its transaction rows (by the superuser), then its lines by storeLinesOf. */
    private fun part(
        tenant: UUID,
        type: AggregateType,
        key: String,
        txs: List<Tx>,
    ): StoredAggregate {
        val stored = withStore(tenant) { store, _, _, _ -> store.store(newAggregate(type, key)) }
        superuser { c ->
            txs.forEachIndexed { i, tx ->
                c
                    .prepareStatement(
                        "insert into aggregation.aggregate_transaction (tenant_id, entity_ref, business_unit_ref, " +
                            "aggregate_type, source_key, source_revision, transaction_ref, position, " +
                            "transaction_date, " +
                            "internal_tax_code, amount, claimable_input_tax, taxable_amount, window_start, " +
                            "window_end, window_state, aggregate_id) values (?, 'E-001', 'BU-01', ?, ?, 1, ?, ?, " +
                            "date '2027-03-10', " +
                            "?, '1.00', ?, ?, date '2027-03-01', date '2027-03-31', 'CLOSED', ?)",
                    ).use {
                        it.setObject(1, tenant)
                        it.setString(2, type.name)
                        it.setString(3, key)
                        it.setString(4, "T$i")
                        it.setInt(5, i)
                        it.setString(6, tx.code)
                        it.setString(7, tx.claimable)
                        it.setString(8, tx.taxable)
                        it.setObject(9, stored.aggregateId)
                        it.executeUpdate()
                    }
            }
        }
        withStore(tenant) { _, dsl, _, runner ->
            runner.inTransaction { dsl.storeLinesOf(tenant, stored.aggregateId) }
        }
        return stored
    }

    private fun lines(aggregateId: UUID) =
        rows(
            "select internal_tax_code, amount, taxable_amount from aggregation.aggregate_line " +
                "where aggregate_id = '$aggregateId' order by position",
        )

    private fun payload(
        tenant: UUID,
        stored: StoredAggregate,
        key: String,
    ): JsonNode {
        withStore(tenant) { store, _, outbox, runner ->
            val lines = store.recordedLines("E-001", key, null)
            runner.inTransaction { outbox.ready(stored.copy(aggregate = stored.aggregate.copy(lines = lines))) }
        }
        return mapper.readTree(
            rows(
                "select data::text from aggregation.outbox where tenant_id = '$tenant' " +
                    "and type = '${AggregateReadyOutbox.TYPE}' and subject = '${stored.aggregateId}'",
            ).single()[0],
        )
    }

    private val first = listOf(Tx("S1", "9.00", "100.00"), Tx("S1", "4.00", "50.00"), Tx("Z1", "0.00", "20.00"))

    @Test
    fun `INV-AC-23681 PCP lines sum the taxable amount when every transaction states it`() {
        val tenant = UUID.randomUUID()
        val stored = part(tenant, AggregateType.PCP, "K1", first)
        lines(stored.aggregateId) shouldBe
            listOf(listOf("S1", "13.00", "150.00"), listOf("Z1", "0.00", "20.00"))
    }

    @Test
    fun `INV-AC-23681 PCP line without a complete taxable amount stays NULL`() {
        val tenant = UUID.randomUUID()
        val stored =
            part(
                tenant,
                AggregateType.PCP,
                "K2",
                listOf(Tx("S1", "9.00", "100.00"), Tx("S1", "4.00", null), Tx("Z1", "0.00", "20.00")),
            )
        lines(stored.aggregateId) shouldBe
            listOf(listOf("S1", "13.00", null), listOf("Z1", "0.00", "20.00"))
    }

    @Test
    fun `INV-AC-23681 POS line never holds a taxable amount`() {
        val tenant = UUID.randomUUID()
        val stored = part(tenant, AggregateType.POS, "K3", listOf(Tx("S1", null, "100.00")))
        rows(
            "select taxable_amount from aggregation.aggregate_transaction where aggregate_id = '${stored.aggregateId}'",
        ) shouldBe listOf(listOf("100.00"))
        lines(stored.aggregateId) shouldBe listOf(listOf("S1", "1.00", null))
    }

    @Test
    fun `INV-AC-23681 batch aggregate lines round-trip the taxable amount`() {
        val tenant = UUID.randomUUID()
        val line = AggregateLine("S1", "13.00", "150.00")
        val (found, recorded) =
            withStore(tenant) { store, _, _, _ ->
                store.store(newAggregate(AggregateType.PCP, "K4", listOf(line)))
                store.findByIntakeKey("E-001", "K4", 1)!! to store.recordedLines("E-001", "K4", null)
            }
        found.aggregate.lines shouldBe listOf(line)
        recorded shouldBe listOf(line)
    }

    @Test
    fun `INV-AC-23681 event line states taxableAmount only when stored`() {
        val tenant = UUID.randomUUID()
        val withTaxable = part(tenant, AggregateType.PCP, "K5", first)
        val partial = part(tenant, AggregateType.PCP, "K6", listOf(Tx("S1", "9.00", "100.00"), Tx("S1", "4.00", null)))
        val pos = part(tenant, AggregateType.POS, "K7", listOf(Tx("S1", null, "100.00")))
        val s1 = payload(tenant, withTaxable, "K5").get("linesByInternalTaxCode").get(0)
        s1.get("amount").asString() shouldBe "13.00"
        s1.get("taxableAmount").isString shouldBe true
        s1.get("taxableAmount").asString() shouldBe "150.00"
        listOf(payload(tenant, partial, "K6"), payload(tenant, pos, "K7")).forEach {
            val line = it.get("linesByInternalTaxCode").get(0)
            line.has("taxableAmount") shouldBe false
            line.propertyNames().toSet() shouldBe setOf("internalTaxCode", "amount")
        }
    }
}
