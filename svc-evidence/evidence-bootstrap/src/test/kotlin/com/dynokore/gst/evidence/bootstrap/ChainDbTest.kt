package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.events.CanonicalVersionCreatedHandler
import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.JooqLocatorStore
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.AppliedRecord.Companion.APPLIED_RECORD
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.DocumentLocator.Companion.DOCUMENT_LOCATOR
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.PendingRecord.Companion.PENDING_RECORD
import com.dynokore.gst.evidence.application.chain.LocatorRecorder
import com.dynokore.gst.evidence.application.chain.RecordAppender
import com.dynokore.gst.evidence.domain.chain.AuditChainKey
import com.dynokore.gst.evidence.domain.chain.DocumentLocator
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.EvidenceRecord
import com.dynokore.platform.evidencestore.JdbcEvidenceLedger
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/**
 * INV-Q-7213 A through the service's repositories on the TPL-18 context over PostgreSQL (P109): the locator consumer
 * handler, the record appender, the pending store and the applied marker.
 */
class ChainDbTest {
    private val actor = "svc:evidence-svc"

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val appender: RecordAppender = ChainWiring.recordAppender(dsl)
        val recorder: LocatorRecorder = ChainWiring.locatorRecorder(dsl)
        val handler = CanonicalVersionCreatedHandler(recorder)

        fun ledger(kind: RecordKind) = JdbcEvidenceLedger(dsl, ChainWiring.SCHEMA, kind.table)
    }

    private fun run(
        dir: Path,
        block: Boundary.(TenantId) -> Unit,
    ) {
        EvidenceContext.online(dir).run { context -> Boundary(context).block(TenantId(UUID.randomUUID())) }
    }

    private fun <T> Boundary.tx(
        tenant: TenantId,
        body: () -> T,
    ): T = scope.bind(tenant, actor) { runner.inTransaction { body() } }

    private fun Boundary.chain(
        tenant: TenantId,
        kind: RecordKind,
        entity: String,
        period: String,
    ): List<EvidenceRecord> =
        tx(tenant) { ledger(kind).findByEntityAndPeriod(tenant, EntityRef(entity), PeriodRef(period)) }

    private fun Boundary.applied(
        tenant: TenantId,
        eventId: String,
    ): Int = tx(tenant) { dsl.fetchCount(APPLIED_RECORD, APPLIED_RECORD.SOURCE_EVENT_ID.eq(eventId)) }

    private val doc = UUID.fromString("0b1f2c3d-0000-4000-8000-000000000001")
    private val at = Instant.parse("2026-10-20T10:00:00Z")

    private fun locator(
        document: UUID = doc,
        version: Int = 1,
        entity: String = "ENT-3F2A9C",
        period: String = "2026-Q1",
        number: String? = "INV-001",
    ) = DocumentLocator(document, version, entity, period, LocalDate.of(2026, 1, 15), "trace-1", number)

    @Suppress("LongParameterList") // test data builder with defaults
    private fun created(
        tenant: TenantId,
        eventId: String,
        entity: EventEntityRef = EventEntityRef("ENT-3F2A9C"),
        version: Int = 1,
        period: String = "2026-Q1",
        number: String? = "INV-001",
    ): Envelope {
        val canonical =
            if (number == null) "{}" else """{"documentNumber":{"value":"$number","classification":"c"}}"""
        return Envelope(
            id = EventId(eventId),
            source = "/gst/canonical-svc",
            type = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1",
            subject = doc.toString(),
            time = at,
            tenant = tenant,
            entity = entity,
            docUuid = DocUuid(doc),
            transactionDate = LocalDate.of(2026, 1, 15),
            docSequence = version.toLong(),
            instanceId = "canonical-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson =
                """{"docUuid":"$doc","version":$version,"transactionDate":"2026-01-15",""" +
                    """"prescribedPeriodRef":"$period",""" +
                    """"traceReference":"trace-1","canonicalDocument":$canonical}""",
        )
    }

    @Suppress("LongParameterList") // test data builder with defaults
    private fun Boundary.pend(
        tenant: TenantId,
        kind: RecordKind,
        eventId: String,
        arrivedAt: Instant = at,
        version: Int = 1,
        document: UUID = doc,
    ) = tx(tenant) {
        val content = """{"id":"$eventId"}""".toByteArray()
        appender.appendBound(tenant, kind, document, version, eventId, content, arrivedAt)
    }

    @Test
    fun `INV-AC-7215 a canonical version event stores one locator, a repeat stores nothing`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        tx(tenant) { handler.handle(created(tenant, "ev-1")) } shouldBe HandleOutcome.APPLIED
        tx(tenant) { handler.handle(created(tenant, "ev-1")) } shouldBe HandleOutcome.IGNORED
        tx(tenant) { handler.handle(created(tenant, "ev-2")) } shouldBe HandleOutcome.IGNORED
        val row = tx(tenant) { JooqLocatorStore(dsl).find(tenant, doc, 1) }
        row shouldBe locator()
        tx(tenant) { dsl.fetchCount(DOCUMENT_LOCATOR) } shouldBe 1
        tx(tenant) { dsl.select(DOCUMENT_LOCATOR.SOURCE_EVENT_ID).from(DOCUMENT_LOCATOR).fetchOne(0) } shouldBe "ev-1"
    }

    @Test
    fun `the period is read from prescribedPeriodRef and an absent document number is stored as null`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        tx(tenant) { handler.handle(created(tenant, "ev-1", period = "2026-M03", number = null)) }
        val row = tx(tenant) { JooqLocatorStore(dsl).find(tenant, doc, 1) }!!
        row.periodRef shouldBe "2026-M03"
        row.transactionDate shouldBe LocalDate.of(2026, 1, 15)
        row.documentNumber shouldBe null
    }

    @Test
    fun `a pending decision trace is appended once to the chain of the locator in the transaction that stores it`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        pend(tenant, RecordKind.DECISION_TRACE, "dt-1")
        chain(tenant, RecordKind.DECISION_TRACE, "ENT-3F2A9C", "2026-Q1").size shouldBe 0
        applied(tenant, "dt-1") shouldBe 0
        tx(tenant) { dsl.fetchCount(PENDING_RECORD) } shouldBe 1
        tx(tenant) { handler.handle(created(tenant, "ev-1")) }
        val records = chain(tenant, RecordKind.DECISION_TRACE, "ENT-3F2A9C", "2026-Q1")
        records.size shouldBe 1
        records.single().index.docUuid shouldBe doc
        records.single().index.invoiceNumber shouldBe "INV-001"
        applied(tenant, "dt-1") shouldBe 1
        tx(tenant) { appender.drainPending(tenant, doc, 1) } shouldBe 0
        chain(tenant, RecordKind.DECISION_TRACE, "ENT-3F2A9C", "2026-Q1").size shouldBe 1
        tx(tenant) { dsl.fetchCount(PENDING_RECORD) } shouldBe 1
    }

    @Test
    fun `pending records are appended in the order of arrival to the table of their kind`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        pend(tenant, RecordKind.ACKNOWLEDGEMENT, "ack-b", arrivedAt = at.plusSeconds(5))
        pend(tenant, RecordKind.DECISION_TRACE, "dt-b", arrivedAt = at.plusSeconds(2))
        pend(tenant, RecordKind.DECISION_TRACE, "dt-a", arrivedAt = at.plusSeconds(2))
        pend(tenant, RecordKind.DECISION_TRACE, "dt-0", arrivedAt = at)
        tx(tenant) { handler.handle(created(tenant, "ev-1")) }
        chain(tenant, RecordKind.DECISION_TRACE, "ENT-3F2A9C", "2026-Q1").map { String(it.content) } shouldBe
            listOf("""{"id":"dt-0"}""", """{"id":"dt-a"}""", """{"id":"dt-b"}""")
        chain(tenant, RecordKind.ACKNOWLEDGEMENT, "ENT-3F2A9C", "2026-Q1").size shouldBe 1
        chain(tenant, RecordKind.EVIDENCE_PAYLOAD, "ENT-3F2A9C", "2026-Q1").size shouldBe 0
    }

    @Test
    fun `a record that arrives after its locator is appended at once and a redelivery appends nothing more`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        tx(tenant) { handler.handle(created(tenant, "ev-1")) }
        pend(tenant, RecordKind.EVIDENCE_PAYLOAD, "pl-1")
        pend(tenant, RecordKind.EVIDENCE_PAYLOAD, "pl-1")
        chain(tenant, RecordKind.EVIDENCE_PAYLOAD, "ENT-3F2A9C", "2026-Q1").size shouldBe 1
        applied(tenant, "pl-1") shouldBe 1
        tx(tenant) { dsl.fetchCount(PENDING_RECORD) } shouldBe 0
    }

    @Test
    fun `a pending record applied by a redelivery is not appended again by the drain`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        pend(tenant, RecordKind.DECISION_TRACE, "dt-1")
        pend(tenant, RecordKind.DECISION_TRACE, "dt-2", arrivedAt = at.plusSeconds(1))
        // the locator is stored without the drain, then dt-1 is delivered again and applied directly
        tx(tenant) { JooqLocatorStore(dsl).insert(tenant, "ev-1", locator(), at) }
        pend(tenant, RecordKind.DECISION_TRACE, "dt-1")
        chain(tenant, RecordKind.DECISION_TRACE, "ENT-3F2A9C", "2026-Q1").size shouldBe 1
        tx(tenant) { appender.drainPending(tenant, doc, 1) } shouldBe 1
        chain(tenant, RecordKind.DECISION_TRACE, "ENT-3F2A9C", "2026-Q1").map { String(it.content) } shouldBe
            listOf("""{"id":"dt-1"}""", """{"id":"dt-2"}""")
    }

    @Test
    fun `the pending records of two documents are drained apart`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        val other = UUID.fromString("0b1f2c3d-0000-4000-8000-000000000002")
        pend(tenant, RecordKind.DECISION_TRACE, "dt-1")
        pend(tenant, RecordKind.DECISION_TRACE, "dt-9", document = other)
        tx(tenant) { handler.handle(created(tenant, "ev-1")) }
        chain(tenant, RecordKind.DECISION_TRACE, "ENT-3F2A9C", "2026-Q1").size shouldBe 1
        applied(tenant, "dt-9") shouldBe 0
    }

    @Test
    fun `an audit entry goes to the audit_log chain of its entity or of TENANT and a redelivery appends nothing more`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        val entityKey = AuditChainKey.of("ENT-3F2A9C", Instant.parse("2026-10-31T23:59:59Z"))
        val tenantKey = AuditChainKey.of(null, Instant.parse("2026-11-01T00:00:00Z"))
        repeat(2) {
            tx(tenant) { appender.appendAudit(tenant, entityKey, "au-1", """{"a":1}""".toByteArray(), at) }
            tx(tenant) { appender.appendAudit(tenant, tenantKey, "au-2", """{"a":2}""".toByteArray(), at) }
        }
        chain(tenant, RecordKind.AUDIT_LOG, "ENT-3F2A9C", "AUDIT-2026-10").size shouldBe 1
        chain(tenant, RecordKind.AUDIT_LOG, "TENANT", "AUDIT-2026-11").size shouldBe 1
        applied(tenant, "au-1") shouldBe 1
        tx(tenant) {
            dsl
                .select(APPLIED_RECORD.SEQ)
                .from(APPLIED_RECORD)
                .where(APPLIED_RECORD.SOURCE_EVENT_ID.eq("au-2"))
                .fetchOne(0)
        } shouldBe 0L
    }

    @Test
    fun `an audit entry is not accepted as a document bound record`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        shouldThrow<IllegalArgumentException> { pend(tenant, RecordKind.AUDIT_LOG, "au-1") }
    }
}
