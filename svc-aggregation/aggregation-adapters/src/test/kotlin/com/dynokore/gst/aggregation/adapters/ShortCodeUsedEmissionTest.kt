package com.dynokore.gst.aggregation.adapters

import com.dynokore.gst.aggregation.adapters.events.ShortCodeUsedOutbox
import com.dynokore.gst.aggregation.adapters.persistence.JooqAggregateStore
import com.dynokore.gst.aggregation.adapters.persistence.JooqUsedShortCodes
import com.dynokore.gst.aggregation.application.DuplicateIntakeKey
import com.dynokore.gst.aggregation.application.NewAggregate
import com.dynokore.gst.aggregation.domain.AggregateType
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
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * The record of used short codes and the `ShortCodeUsed` outbox rows written by the store of an aggregate (INV-AC-2779,
 * INV-Q-11266 A), over the Testcontainers database as the `aggregation_runtime` login with the real JDBC outbox writer.
 */
class ShortCodeUsedEmissionTest {
    private val mapper = ObjectMapper()
    private val type = ShortCodeUsedOutbox.TYPE

    private class Rollback : RuntimeException("rolled back by the test")

    /** A store over one runtime-role connection bound to [tenant]; the runner rolls back instead when [rollback]. */
    private fun <T> withStore(
        tenant: UUID,
        eventType: String = ShortCodeUsedOutbox.TYPE,
        rollback: () -> Boolean = { false },
        block: (JooqAggregateStore) -> T,
    ): T =
        AggregationDb.connect(AggregationDb.RUNTIME, AggregationDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            val runner =
                object : TransactionRunner {
                    override fun <R> inTransaction(block: () -> R): R {
                        SignedBindings.bind(connection, tenant)
                        return try {
                            block().also {
                                if (rollback()) throw Rollback()
                                connection.commit()
                            }
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
                    listOf(CatalogueEntry(eventType, "gst.aggregation.short-code-used.v1", setOf("aggregation-svc"))),
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
            block(JooqAggregateStore(dsl, runner, tenants, used))
        }

    private fun aggregate(
        entityCode: String,
        unitCode: String,
        key: String,
    ) = NewAggregate(
        entityRef = "E-001",
        businessUnitRef = "BU-01",
        aggregateType = AggregateType.POS,
        periodStart = LocalDate.of(2027, 3, 1),
        periodEnd = LocalDate.of(2027, 3, 31),
        sourceKey = key,
        sourceRevision = 1,
        sequence = 1,
        entityCode = entityCode,
        businessUnitCode = unitCode,
        documentNumber = "AGG-$entityCode-POS-$unitCode-20270331-$key",
        issueDate = LocalDate.of(2027, 3, 31),
        transactionDate = LocalDate.of(2027, 3, 31),
    )

    private fun rows(sql: String): List<List<String>> =
        AggregationDb.superuser().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    generateSequence {
                        if (rows.next()) {
                            (1..rows.metaData.columnCount).map {
                                rows.getString(
                                    it,
                                )
                            }
                        } else {
                            null
                        }
                    }.toList()
                }
            }
        }

    private fun usedCodes(tenant: UUID) =
        rows("select code from aggregation.used_short_code where tenant_id = '$tenant' order by code").map { it[0] }

    /** (code, nodeKind, nodeRef, aggregateId, documentNumber) of each `ShortCodeUsed` row of the tenant, by code. */
    private fun events(tenant: UUID): List<List<String>> =
        rows("select data::text from aggregation.outbox where tenant_id = '$tenant' and type = '$type' order by seq")
            .map { mapper.readTree(it[0]) }
            .map {
                listOf("code", "nodeKind", "nodeRef", "aggregateId", "documentNumber").map { f ->
                    it.get(f).asString()
                }
            }

    @Test
    fun `INV-AC-2779 a first aggregate records both codes and emits one event per code with the exact payload`() {
        val tenant = UUID.randomUUID()
        val first = aggregate("E1", "B1", "K1")
        val stored = withStore(tenant) { it.store(first) }
        usedCodes(tenant) shouldBe listOf("B1", "E1")
        val id = stored.aggregateId.toString()
        events(tenant) shouldBe
            listOf(
                listOf("E1", "ENTITY", "E-001", id, first.documentNumber),
                listOf("B1", "BUSINESS_UNIT", "BU-01", id, first.documentNumber),
            )
    }

    @Test
    fun `INV-AC-2779 a second aggregate with the same codes in the same tenant emits no new event`() {
        val tenant = UUID.randomUUID()
        withStore(tenant) {
            it.store(aggregate("E1", "B1", "K1"))
            it.store(aggregate("E1", "B1", "K2"))
        }
        usedCodes(tenant) shouldBe listOf("B1", "E1")
        events(tenant).size shouldBe 2
    }

    @Test
    fun `INV-AC-2779 a second aggregate with a new business unit code adds one row and one event`() {
        val tenant = UUID.randomUUID()
        val second =
            withStore(tenant) {
                it.store(aggregate("E1", "B1", "K1"))
                it.store(aggregate("E1", "B2", "K2"))
            }
        usedCodes(tenant) shouldBe listOf("B1", "B2", "E1")
        events(tenant).size shouldBe 3
        events(tenant).last() shouldBe
            listOf("B2", "BUSINESS_UNIT", "BU-01", second.aggregateId.toString(), second.aggregate.documentNumber)
    }

    @Test
    fun `INV-AC-2779 the same codes in another tenant emit again`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        withStore(a) { it.store(aggregate("E1", "B1", "K1")) }
        withStore(b) { it.store(aggregate("E1", "B1", "K1")) }
        usedCodes(a) shouldBe listOf("B1", "E1")
        usedCodes(b) shouldBe listOf("B1", "E1")
        events(a).size shouldBe 2
        events(b).size shouldBe 2
    }

    @Test
    fun `INV-AC-2779 a rolled-back store leaves no used code row and no outbox row`() {
        val tenant = UUID.randomUUID()
        shouldThrow<Rollback> { withStore(tenant, rollback = { true }) { it.store(aggregate("E1", "B1", "K1")) } }
        usedCodes(tenant) shouldBe emptyList()
        events(tenant) shouldBe emptyList()
        rows("select count(*) from aggregation.aggregate where tenant_id = '$tenant'")[0][0] shouldBe "0"
    }

    @Test
    fun `INV-AC-2779 a replay of the intake key stores nothing and leaves both tables unchanged`() {
        val tenant = UUID.randomUUID()
        withStore(tenant) {
            it.store(aggregate("E1", "B1", "K1"))
            shouldThrow<DuplicateIntakeKey> { it.store(aggregate("E1", "B1", "K1")) }
        }
        usedCodes(tenant) shouldBe listOf("B1", "E1")
        events(tenant).size shouldBe 2
    }

    @Test
    fun `INV-AC-2779 the type spelled ShortCodeUse is refused by the emission guard and nothing is stored`() {
        val tenant = UUID.randomUUID()
        // the catalogue lists only the misspelled type, so the guard refuses the real one
        shouldThrow<Exception> {
            withStore(tenant, eventType = "com.dynokore.gst.aggregation.ShortCodeUse.v1") {
                it.store(aggregate("E1", "B1", "K1"))
            }
        }
        usedCodes(tenant) shouldBe emptyList()
    }
}
