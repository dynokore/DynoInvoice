package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.AppliedRecord.Companion.APPLIED_RECORD
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.EventDeadLetter.Companion.EVENT_DEAD_LETTER
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.PendingRecord.Companion.PENDING_RECORD
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.ChainVerificationResult
import com.dynokore.platform.evidencestore.ContentHash
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.EvidenceRecord
import com.dynokore.platform.evidencestore.JdbcEvidenceLedger
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/**
 * INV-AC-7216 through the real inbox consumers (`JdbcInboxConsumer`) of the two record topics, with the handlers and
 * stores of the TPL-18 context over PostgreSQL (P109). Only the event-log read is a list in memory.
 */
class RecordConsumersTest {
    private val actor = "svc:evidence-svc"
    private val doc = UUID.fromString("0b1f2c3d-0000-4000-8000-000000000003")
    private val entity = "ENT-3F2A9C"
    private val at = Instant.parse("2026-10-19T10:00:00Z")
    private val json = EnvelopeJson()

    private class Log : EventLogReader {
        private val records = mutableListOf<LogRecord>()

        fun add(envelopeJson: String) {
            records += LogRecord(records.size + 1L, envelopeJson)
        }

        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> = records.filter { it.offset > afterOffset }.take(limit)
    }

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val locatorLog = Log()
        val traceLog = Log()
        val auditLog = Log()

        // a consumer name of its own per test: the offset row is platform-scoped and the database is shared
        private val name = LocatorConsumerConfiguration.CONSUMER + "-" + UUID.randomUUID()
        val consumerName get() = name
        val catalogue: EventCatalogue = context.getBean(EventCatalogue::class.java)

        private fun consumer(
            topic: String,
            log: Log,
            handler: com.dynokore.platform.outboxandqueue.EventHandler,
        ) = JdbcInboxConsumer(
            ConsumerSubscription(name, topic, ChainWiring.SCHEMA),
            catalogue,
            log,
            scope,
            dsl,
            handler,
            RebuildRequiredSink { },
            Clock.systemUTC(),
        )

        val locatorConsumer = consumer(LocatorConsumerConfiguration.TOPIC, locatorLog, ChainWiring.locatorHandler(dsl))
        val traceConsumer =
            consumer(
                RecordConsumerConfiguration.DECISION_TRACE_TOPIC,
                traceLog,
                ChainWiring.decisionTraceHandler(dsl),
            )
        val auditConsumer =
            consumer(
                RecordConsumerConfiguration.AUDIT_ENTRY_TOPIC,
                auditLog,
                ChainWiring.auditEntryHandler(dsl),
            )
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
        entityRef: String,
        period: String,
    ): List<EvidenceRecord> =
        tx(tenant) {
            JdbcEvidenceLedger(dsl, ChainWiring.SCHEMA, kind.table)
                .findByEntityAndPeriod(tenant, EntityRef(entityRef), PeriodRef(period))
        }

    private fun Boundary.traces(tenant: TenantId) = chain(tenant, RecordKind.DECISION_TRACE, entity, "2026-Q1")

    private fun Boundary.applied(
        tenant: TenantId,
        eventId: String,
    ) = tx(tenant) { dsl.fetch(APPLIED_RECORD, APPLIED_RECORD.SOURCE_EVENT_ID.eq(eventId)) }

    private fun Boundary.pendingCount(tenant: TenantId) = tx(tenant) { dsl.fetchCount(PENDING_RECORD) }

    private fun Boundary.deadLetters(tenant: TenantId) =
        tx(tenant) { dsl.fetchCount(EVENT_DEAD_LETTER, EVENT_DEAD_LETTER.CONSUMER.eq(consumerName)) }

    private fun envelope(
        tenant: TenantId,
        eventId: String,
        type: String,
        source: String,
        entityRef: EventEntityRef,
        docSequence: Long?,
        data: String,
        docUuid: DocUuid? = null,
    ) = json.write(
        Envelope(
            id = EventId(eventId),
            source = source,
            type = type,
            subject = doc.toString(),
            time = at,
            tenant = tenant,
            entity = entityRef,
            docUuid = docUuid,
            transactionDate = docUuid?.let { LocalDate.of(2026, 1, 15) },
            docSequence = docSequence,
            instanceId = "$source-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = data,
        ),
    )

    private fun locator(
        tenant: TenantId,
        eventId: String,
    ) = envelope(
        tenant,
        eventId,
        "com.dynokore.gst.canonical.CanonicalVersionCreated.v1",
        "/gst/canonical-svc",
        EventEntityRef(entity),
        1,
        """{"docUuid":"$doc","version":1,"transactionDate":"2026-01-15","prescribedPeriodRef":"2026-Q1",""" +
            """"traceReference":"trace-1",""" +
            """"canonicalDocument":{"documentNumber":{"value":"INV-001","classification":"c"}}}""",
        DocUuid(doc),
    )

    private fun trace(
        tenant: TenantId,
        eventId: String,
        decisionTrace: String = """{"rule":"R1","outcome":"ok"}""",
        docSequence: Long? = 1,
    ) = envelope(
        tenant,
        eventId,
        "com.dynokore.gst.canonical.DecisionTraceRecorded.v1",
        "/gst/canonical-svc",
        EventEntityRef(entity),
        docSequence,
        """{"docUuid":"$doc","version":1,"traceReference":"trace-1","decisionTrace":$decisionTrace}""",
        DocUuid(doc),
    )

    private fun audit(
        tenant: TenantId,
        eventId: String,
        entityRef: EventEntityRef? = EventEntityRef(entity),
        data: String = """{"scope":"invoice","action":"read","actor":"user-1"}""",
    ) = envelope(
        tenant,
        eventId,
        "com.dynokore.gst.platform.AuditEntryRecorded.v1",
        "/gst/console-bff",
        entityRef ?: EventEntityRef.TENANT_WIDE,
        null,
        data,
    )

    private fun result(
        applied: Int,
        duplicates: Int,
        deadLettered: Int,
    ) = PollResult(applied = applied, ignored = 0, duplicates = duplicates, stale = 0, deadLettered = deadLettered)

    private fun sha256Link(
        previous: String?,
        content: ByteArray,
    ): String = ContentHash.of(((previous ?: "").toByteArray(Charsets.US_ASCII)) + content).hex

    @Test
    fun `INV-AC-7216 pending decision trace drained once by the locator, audit entry in the audit chain`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        traceLog.add(trace(tenant, "dt-1"))
        traceConsumer.pollOnce() shouldBe result(1, 0, 0)
        traces(tenant).size shouldBe 0
        pendingCount(tenant) shouldBe 1
        applied(tenant, "dt-1").size shouldBe 0

        locatorLog.add(locator(tenant, "ev-1"))
        locatorConsumer.pollOnce().applied shouldBe 1
        val appended = traces(tenant).single()
        String(appended.content) shouldBe """{"outcome":"ok","rule":"R1"}"""
        appended.index.docUuid shouldBe doc
        appended.index.invoiceNumber shouldBe "INV-001"
        val row = applied(tenant, "dt-1").single()
        row.entityRef shouldBe entity
        row.periodRef shouldBe "2026-Q1"
        row.seq shouldBe appended.sequence
        row.kind shouldBe "decision_trace"

        traceLog.add(trace(tenant, "dt-1")) // redelivered
        traceConsumer.pollOnce().duplicates shouldBe 1
        traces(tenant).size shouldBe 1
        pendingCount(tenant) shouldBe 1
        applied(tenant, "dt-1").size shouldBe 1

        auditLog.add(audit(tenant, "au-1"))
        auditConsumer.pollOnce().applied shouldBe 1
        val entry = chain(tenant, RecordKind.AUDIT_LOG, entity, "AUDIT-2026-10").single()
        String(entry.content) shouldBe """{"action":"read","actor":"user-1","scope":"invoice"}"""
        applied(tenant, "au-1").single().periodRef shouldBe "AUDIT-2026-10"
    }

    @Test
    fun `a decision trace after the locator is appended at once, with an older docsequence not dropped`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        locatorLog.add(locator(tenant, "ev-1"))
        locatorConsumer.pollOnce().applied shouldBe 1
        traceLog.add(trace(tenant, "dt-1", docSequence = 5))
        traceLog.add(trace(tenant, "dt-2", """{"rule":"R2"}""", docSequence = 2))
        traceConsumer.pollOnce() shouldBe result(2, 0, 0)
        traces(tenant).map { String(it.content) } shouldBe
            listOf("""{"outcome":"ok","rule":"R1"}""", """{"rule":"R2"}""")
        pendingCount(tenant) shouldBe 0
    }

    @Test
    fun `two events with the same content and different event ids are two records, key order does not matter`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        locatorLog.add(locator(tenant, "ev-1"))
        locatorConsumer.pollOnce()
        traceLog.add(trace(tenant, "dt-1", """{"b":[{"y":1,"x":2}],"a":"1"}"""))
        traceLog.add(trace(tenant, "dt-2", """{"a":"1","b":[{"x":2,"y":1}]}"""))
        traceConsumer.pollOnce().applied shouldBe 2
        val records = traces(tenant)
        records.size shouldBe 2
        records.map { String(it.content) }.toSet() shouldBe setOf("""{"a":"1","b":[{"x":2,"y":1}]}""")
    }

    @Test
    fun `an audit entry without an entity reference is appended to the tenant-wide chain, a redelivery stores nothing`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        auditLog.add(audit(tenant, "au-1", entityRef = null))
        auditLog.add(audit(tenant, "au-1", entityRef = null))
        auditConsumer.pollOnce() shouldBe result(1, 1, 0)
        val tenantWide = EventEntityRef.TENANT_WIDE.value
        chain(tenant, RecordKind.AUDIT_LOG, tenantWide, "AUDIT-2026-10").size shouldBe 1
        applied(tenant, "au-1").single().entityRef shouldBe tenantWide
    }

    @Test
    fun `the content hash of an appended record is the SHA-256 chain link of the canonical JSON`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        auditLog.add(audit(tenant, "au-1"))
        auditLog.add(audit(tenant, "au-2", data = """{"scope":"s","action":"write","actor":"u2"}"""))
        auditConsumer.pollOnce().applied shouldBe 2
        val records = chain(tenant, RecordKind.AUDIT_LOG, entity, "AUDIT-2026-10")
        val first = sha256Link(null, """{"action":"read","actor":"user-1","scope":"invoice"}""".toByteArray())
        records[0].contentHash.hex shouldBe first
        records[1].contentHash.hex shouldBe
            sha256Link(first, """{"action":"write","actor":"u2","scope":"s"}""".toByteArray())
        tx(tenant) {
            JdbcEvidenceLedger(dsl, ChainWiring.SCHEMA, RecordKind.AUDIT_LOG.table)
                .verifyChain(tenant, EntityRef(entity), PeriodRef("AUDIT-2026-10"))
        } shouldBe ChainVerificationResult.Valid(2)
    }

    @Test
    fun `an audit entry without actor is dead-lettered and stores nothing`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        auditLog.add(audit(tenant, "au-1", data = """{"scope":"invoice","action":"read"}"""))
        auditConsumer.pollOnce() shouldBe result(0, 0, 1)
        deadLetters(tenant) shouldBe 1
        chain(tenant, RecordKind.AUDIT_LOG, entity, "AUDIT-2026-10").size shouldBe 0
        applied(tenant, "au-1").size shouldBe 0
    }
}
