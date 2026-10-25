package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.DocumentLocator.Companion.DOCUMENT_LOCATOR
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.EventDeadLetter.Companion.EVENT_DEAD_LETTER
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.Inbox.Companion.INBOX
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.PendingRecord.Companion.PENDING_RECORD
import com.dynokore.gst.evidence.domain.chain.RecordKind
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
 * INV-AC-7215 through the real inbox consumer (`JdbcInboxConsumer`) on the inbox, offset and dead-letter tables of the
 * consumer-side migration, with the handler and the stores of the TPL-18 context over PostgreSQL (P109). Only the
 * event-log read is a list in memory: the `eventlog` schema belongs to the event-log instance.
 */
class LocatorConsumerTest {
    private val actor = "svc:evidence-svc"
    private val doc = UUID.fromString("0b1f2c3d-0000-4000-8000-000000000002")
    private val at = Instant.parse("2026-10-20T10:00:00Z")
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
        val log = Log()

        // a consumer name of its own per test: the offset row is platform-scoped and the database is shared
        val consumerName = LocatorConsumerConfiguration.CONSUMER + "-" + UUID.randomUUID()
        val appender = ChainWiring.recordAppender(dsl)
        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription(
                    consumerName,
                    LocatorConsumerConfiguration.TOPIC,
                    LocatorConsumerConfiguration.SCHEMA,
                ),
                context.getBean(EventCatalogue::class.java),
                log,
                scope,
                dsl,
                ChainWiring.locatorHandler(dsl),
                RebuildRequiredSink { },
                Clock.systemUTC(),
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
    ): List<EvidenceRecord> =
        tx(tenant) {
            JdbcEvidenceLedger(dsl, ChainWiring.SCHEMA, kind.table)
                .findByEntityAndPeriod(tenant, EntityRef("ENT-3F2A9C"), PeriodRef("2026-Q1"))
        }

    private fun created(
        tenant: TenantId,
        eventId: String,
        version: Int = 1,
        docSequence: Long = version.toLong(),
        entityRef: Boolean = true,
    ): String {
        val envelope =
            Envelope(
                id = EventId(eventId),
                source = "/gst/canonical-svc",
                type = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1",
                subject = doc.toString(),
                time = at,
                tenant = tenant,
                entity = EventEntityRef("ENT-3F2A9C"),
                docUuid = DocUuid(doc),
                transactionDate = LocalDate.of(2026, 1, 15),
                docSequence = docSequence,
                instanceId = "canonical-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson =
                    """{"docUuid":"$doc","version":$version,"transactionDate":"2026-01-15",""" +
                        """"prescribedPeriodRef":"2026-Q1","traceReference":"trace-1",""" +
                        """"canonicalDocument":{"documentNumber":{"value":"INV-001","classification":"c"}}}""",
            )
        val text = json.write(envelope)
        return if (entityRef) text else text.replace(Regex(""","entityref":"[^"]*""""), "")
    }

    private fun Boundary.locators(
        tenant: TenantId,
        version: Int,
    ) = tx(tenant) {
        dsl.fetch(DOCUMENT_LOCATOR, DOCUMENT_LOCATOR.DOC_UUID.eq(doc).and(DOCUMENT_LOCATOR.VERSION.eq(version)))
    }

    private fun Boundary.deadLetters(tenant: TenantId) =
        tx(tenant) { dsl.fetchCount(EVENT_DEAD_LETTER, EVENT_DEAD_LETTER.CONSUMER.eq(consumerName)) }

    @Test
    fun `INV-AC-7215 a canonical version event stores one locator through the inbox, a repeat stores nothing`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        log.add(created(tenant, "ev-1"))
        consumer.pollOnce() shouldBe PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
        val row = locators(tenant, 1).single()
        row.entityRef shouldBe "ENT-3F2A9C"
        row.periodRef shouldBe "2026-Q1"
        row.documentNumber shouldBe "INV-001"
        row.transactionDate shouldBe LocalDate.of(2026, 1, 15)
        row.traceReference shouldBe "trace-1"
        row.sourceEventId shouldBe "ev-1"
        tx(tenant) { dsl.fetchCount(INBOX) } shouldBe 1

        log.add(created(tenant, "ev-1")) // the same envelope id delivered again
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 1, stale = 0, deadLettered = 0)
        locators(tenant, 1).size shouldBe 1
        tx(tenant) { dsl.fetchCount(INBOX) } shouldBe 1
    }

    @Test
    fun `INV-AC-7215 an event with another envelope id for the same version stores no second locator`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        log.add(created(tenant, "ev-1"))
        log.add(created(tenant, "ev-2", docSequence = 2))
        val result = consumer.pollOnce()
        result.applied shouldBe 1
        result.ignored shouldBe 1
        locators(tenant, 1).single().sourceEventId shouldBe "ev-1"
    }

    @Test
    fun `INV-AC-7215 an event with no entity reference stores nothing and is dead-lettered`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        log.add(created(tenant, "ev-1"))
        log.add(created(tenant, "ev-3", version = 2, entityRef = false))
        consumer.pollOnce() shouldBe PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)
        locators(tenant, 1).size shouldBe 1
        locators(tenant, 2).size shouldBe 0
        deadLetters(tenant) shouldBe 1
        tx(tenant) {
            dsl
                .select(EVENT_DEAD_LETTER.REASON)
                .from(EVENT_DEAD_LETTER)
                .fetchOne(0)
        } shouldBe "entityref is required"
        consumer.pollOnce().total shouldBe 0
    }

    @Test
    fun `a pending decision trace is appended once when the locator arrives through the inbox`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        tx(tenant) {
            appender.appendBound(
                tenant,
                RecordKind.DECISION_TRACE,
                doc,
                1,
                "dt-1",
                """{"id":"dt-1"}""".toByteArray(),
                at,
            )
        }
        chain(tenant, RecordKind.DECISION_TRACE).size shouldBe 0
        log.add(created(tenant, "ev-1"))
        consumer.pollOnce().applied shouldBe 1
        chain(tenant, RecordKind.DECISION_TRACE).map { String(it.content) } shouldBe listOf("""{"id":"dt-1"}""")
        log.add(created(tenant, "ev-1")) // redelivered: nothing is drained twice
        consumer.pollOnce().duplicates shouldBe 1
        tx(tenant) { appender.drainPending(tenant, doc, 1) } shouldBe 0
        chain(tenant, RecordKind.DECISION_TRACE).size shouldBe 1
        tx(tenant) { dsl.fetchCount(PENDING_RECORD) } shouldBe 1
    }
}
