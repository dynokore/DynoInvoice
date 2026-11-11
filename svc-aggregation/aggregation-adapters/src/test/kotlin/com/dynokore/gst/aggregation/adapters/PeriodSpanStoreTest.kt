package com.dynokore.gst.aggregation.adapters

import com.dynokore.gst.aggregation.adapters.periods.ConsumedPeriodEvents
import com.dynokore.gst.aggregation.adapters.periods.JooqPeriodSpanStore
import com.dynokore.gst.aggregation.adapters.periods.PeriodRecordsChangedHandler
import com.dynokore.gst.aggregation.adapters.periods.StoredPeriodBoundaries
import com.dynokore.gst.aggregation.application.PeriodSpans
import com.dynokore.gst.aggregation.domain.PeriodSpan
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The period-span read model over the migration V7__period_spans.sql, as the runtime role sees it (CS-19): the store,
 * the handler of `PeriodRecordsChanged` and the source behind `PeriodBoundarySource` (INV-REQ-435).
 */
class PeriodSpanStoreTest {
    private var tenant: UUID = UUID.randomUUID()

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = TenantId(tenant)

            override fun currentActor(): String = SignedBindings.ACTOR

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private val passThrough =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

    private val mapper = JsonMapper.builder().build()

    /** One short transaction per call on a runtime connection with the signed binding of the current tenant. */
    private fun <T> transaction(block: (JooqPeriodSpanStore) -> T): T =
        AggregationDb.connect(AggregationDb.RUNTIME, AggregationDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            val store = JooqPeriodSpanStore(DSL.using(connection, SQLDialect.POSTGRES), passThrough, tenants)
            try {
                block(store).also { connection.commit() }
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }

    private val handler =
        object {
            fun handle(event: Envelope): HandleOutcome =
                transaction { PeriodRecordsChangedHandler(it, mapper).handle(event) }
        }
    private val source =
        object {
            fun spans(
                entityRef: String,
                from: LocalDate,
                to: LocalDate,
            ): PeriodSpans = transaction { StoredPeriodBoundaries(it).spans(entityRef, from, to) }
        }

    private fun span(
        ref: String,
        start: String,
        end: String,
    ) = PeriodSpan(LocalDate.parse(start), LocalDate.parse(end), ref)

    private fun event(
        data: String,
        type: String = ConsumedPeriodEvents.PERIOD_RECORDS_CHANGED,
    ) = Envelope(
        id = EventId(UUID.randomUUID().toString()),
        source = "/gst/tenancy-svc",
        type = type,
        subject = "E-001",
        time = Instant.parse("2027-01-01T00:00:00Z"),
        tenant = TenantId(tenant),
        entity = EntityRef("E-001"),
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "tenancy-svc-0",
        correlation = CorrelationId("c"),
        causation = CausationId("k"),
        dataJson = data,
    )

    private fun payload(
        entity: String,
        version: Long,
        vararg periods: Triple<String, String, String>,
    ): String =
        """{"entityRef":"$entity","version":$version,"periods":[""" +
            periods.joinToString(",") { (ref, start, end) ->
                """{"periodRef":"$ref","periodStart":"$start","periodEnd":"$end"}"""
            } + "]}"

    private val q1 = Triple("P-Q1", "2027-01-01", "2027-03-31")
    private val q2 = Triple("P-Q2", "2027-04-01", "2027-06-30")

    private fun stored(entity: String): List<PeriodSpan>? =
        transaction { it.overlapping(entity, LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1)) }

    @Test
    fun `INV-AC-14492 a higher version replaces the whole set of the entity`() {
        handler.handle(event(payload("E-001", 1, q1))) shouldBe HandleOutcome.APPLIED
        handler.handle(event(payload("E-001", 2, q1, q2))) shouldBe HandleOutcome.APPLIED
        stored("E-001") shouldBe
            listOf(span("P-Q1", "2027-01-01", "2027-03-31"), span("P-Q2", "2027-04-01", "2027-06-30"))
        handler.handle(event(payload("E-001", 3, q2))) shouldBe HandleOutcome.APPLIED
        stored("E-001") shouldBe listOf(span("P-Q2", "2027-04-01", "2027-06-30"))
    }

    @Test
    fun `the same version again and a lower version change nothing`() {
        handler.handle(event(payload("E-001", 5, q1))) shouldBe HandleOutcome.APPLIED
        handler.handle(event(payload("E-001", 5, q2))) shouldBe HandleOutcome.IGNORED
        handler.handle(event(payload("E-001", 4, q2))) shouldBe HandleOutcome.IGNORED
        stored("E-001") shouldBe listOf(span("P-Q1", "2027-01-01", "2027-03-31"))
    }

    @Test
    fun `a payload that breaks the contract is a ContractViolation and stores nothing`() {
        val broken =
            listOf(
                """{"entityRef":"E-001","periods":[{"periodRef":"P","periodStart":"2027-01-01","periodEnd":"2027-01-31"}]}""",
                payload("E-001", 1, Triple("P", "2027-02-01", "2027-01-31")),
                payload("E-001", 1, Triple("", "2027-01-01", "2027-01-31")),
                """{"entityRef":"E-001","version":1,"periods":[{"periodStart":"2027-01-01","periodEnd":"2027-01-31"}]}""",
                payload("E-001", 0, q1),
                payload("E-001", 1, q1, q1),
                """{"entityRef":"E-001","version":1}""",
            )
        broken.forEach { shouldThrow<ContractViolation> { handler.handle(event(it)) } }
        stored("E-001") shouldBe null
    }

    @Test
    fun `an event of another type is refused and the handler is bound to PeriodRecordsChanged only`() {
        shouldThrow<IllegalArgumentException> {
            handler.handle(event(payload("E-001", 1, q1), "com.dynokore.gst.tenancy.PeriodExtended.v1"))
        }
        ConsumedPeriodEvents.PERIOD_RECORDS_CHANGED shouldBe "com.dynokore.gst.tenancy.PeriodRecordsChanged.v1"
        ConsumedPeriodEvents.PERIOD_RECORDS_CHANGED_TOPIC shouldBe "gst.tenancy.period-records-changed.v1"
        stored("E-001") shouldBe null
    }

    @Test
    fun `a set for entity A leaves entity B unchanged`() {
        handler.handle(event(payload("E-B", 1, q1))) shouldBe HandleOutcome.APPLIED
        handler.handle(event(payload("E-A", 1, q2))) shouldBe HandleOutcome.APPLIED
        handler.handle(event(payload("E-A", 2, q2))) shouldBe HandleOutcome.APPLIED
        stored("E-B") shouldBe listOf(span("P-Q1", "2027-01-01", "2027-03-31"))
    }

    @Test
    fun `the rows of one tenant are invisible to another and the version is kept per tenant`() {
        handler.handle(event(payload("E-001", 3, q1))) shouldBe HandleOutcome.APPLIED
        val first = tenant
        tenant = UUID.randomUUID()
        stored("E-001") shouldBe null
        handler.handle(event(payload("E-001", 1, q2))) shouldBe HandleOutcome.APPLIED
        stored("E-001") shouldBe listOf(span("P-Q2", "2027-04-01", "2027-06-30"))
        tenant = first
        stored("E-001") shouldBe listOf(span("P-Q1", "2027-01-01", "2027-03-31"))
    }

    @Test
    fun `INV-AC-14492 the source returns the overlapping spans earliest first and Unknown without a set`() {
        source.spans("E-001", LocalDate.of(2027, 3, 28), LocalDate.of(2027, 4, 3)) shouldBe PeriodSpans.Unknown
        handler.handle(event(payload("E-001", 1, q2, q1)))
        source.spans("E-001", LocalDate.of(2027, 3, 28), LocalDate.of(2027, 4, 3)) shouldBe
            PeriodSpans.Known(
                listOf(span("P-Q1", "2027-01-01", "2027-03-31"), span("P-Q2", "2027-04-01", "2027-06-30")),
            )
        source.spans("E-001", LocalDate.of(2027, 4, 2), LocalDate.of(2027, 4, 3)) shouldBe
            PeriodSpans.Known(listOf(span("P-Q2", "2027-04-01", "2027-06-30")))
        source.spans("e-001", LocalDate.of(2027, 3, 28), LocalDate.of(2027, 4, 3)) shouldBe PeriodSpans.Unknown
    }

    @Test
    fun `a stored set with a gap inside the asked range is returned as stored`() {
        handler.handle(event(payload("E-001", 1, q1, Triple("P-Q3", "2027-07-01", "2027-09-30"))))
        source.spans("E-001", LocalDate.of(2027, 3, 28), LocalDate.of(2027, 7, 3)) shouldBe
            PeriodSpans.Known(
                listOf(span("P-Q1", "2027-01-01", "2027-03-31"), span("P-Q3", "2027-07-01", "2027-09-30")),
            )
    }
}
