package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.WorkQueue
import com.dynokore.gst.submission.application.queue.PayloadReadyHandler
import com.dynokore.gst.submission.application.queue.SentResult
import com.dynokore.gst.submission.application.queue.SubmissionWorker
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.ReplayDelivery
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import com.dynokore.platform.outboxandqueue.WorkQueue as WorkQueuePort

/**
 * The `PayloadReady` consumer against a real database (P109): the library `JdbcInboxConsumer` drives
 * `PayloadReadyHandler`, so the inbox row, the `submission` row and the queue item commit together (INV-REQ-816,
 * INV-REQ-1222). The decisive transport call belongs to the attempts slice; here the items `SubmissionWorker` hands
 * out stand for it (coordinator rulings INV-Q-4876, INV-Q-4896).
 */
class PayloadReadyDbTest {
    private val actor = "svc:submission-svc"
    private val json = EnvelopeJson()
    private val bytes = "<Invoice/>".toByteArray()

    /** Replays a fixed list of records, as the event log would. */
    private class ListReader(
        val records: MutableList<LogRecord> = mutableListOf(),
    ) : EventLogReader {
        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> = records.filter { it.offset > afterOffset }.take(limit)
    }

    private class Recording(
        private val inner: EventHandler,
        private val after: (HandleOutcome) -> Unit = {},
    ) : EventHandler {
        val outcomes = mutableListOf<HandleOutcome>()

        override fun handle(event: Envelope): HandleOutcome =
            inner.handle(event).also {
                outcomes += it
                after(it)
            }
    }

    private inner class Kit(
        val context: ApplicationContext,
    ) {
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val worker: SubmissionWorker = context.getBean(SubmissionWorker::class.java)
        val workQueue: WorkQueuePort = context.getBean(WorkQueuePort::class.java)
        val handler: EventHandler = context.getBean("payloadReadyHandler", EventHandler::class.java)
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val catalogue: EventCatalogue = context.getBean(EventCatalogue::class.java)
        val reader = ListReader()
        val tenant: UUID = UUID.randomUUID()
        private var offset = 0L
        private val consumerName = "t7276-" + UUID.randomUUID()

        fun consumer(handler: EventHandler): JdbcInboxConsumer =
            JdbcInboxConsumer(
                ConsumerSubscription(consumerName, SubmissionEvents.PAYLOAD_READY_TOPIC, "submission"),
                catalogue,
                reader,
                scope,
                TenancyJooq.dslContext(OutboxSchema.jooqTables("submission"), tenants, runner),
                handler,
                RebuildRequiredSink { },
            ).also { drainQueue() }

        fun deliver(envelope: Envelope) {
            reader.records += LogRecord(++offset, json.write(envelope))
        }

        fun <T> asTenant(block: () -> T): T = tenants.bind(TenantId(tenant), actor) { runner.inTransaction(block) }

        fun submissionRows(
            doc: String,
            version: Int,
        ): Int =
            asTenant {
                dsl.fetchCount(
                    Submission.SUBMISSION_,
                    Submission.SUBMISSION_.DOC_UUID
                        .eq(doc)
                        .and(Submission.SUBMISSION_.VERSION.eq(version)),
                )
            }

        fun queueRows(doc: String): Int =
            asTenant { dsl.fetchCount(WorkQueue.WORK_QUEUE, WorkQueue.WORK_QUEUE.PARTITION_KEY.eq(doc)) }

        fun inboxRows(): Int =
            asTenant {
                dsl.fetchCount(
                    DSL.table(DSL.name("submission", "inbox")),
                    DSL.field(DSL.name("consumer")).eq(consumerName),
                )
            }

        /** Drains `takeNext` until null: the dispatches this service produces for the transport step. */
        fun dispatches(): List<Pair<String, Int>> {
            val out = mutableListOf<Pair<String, Int>>()
            while (true) {
                val result = worker.takeNext() ?: break
                val sent = result as? SentResult.Sent ?: error("expected Sent, got $result")
                out += sent.dispatch.docUuid to sent.dispatch.version
            }
            return out
        }

        /** `claimNext` is cross-tenant: drops the items other tests left. */
        private fun drainQueue() {
            while (true) {
                val item = workQueue.claimNext() ?: break
                tenants.bind(item.tenant, actor) { runner.inTransaction { workQueue.complete(item) } }
            }
        }

        fun envelope(
            doc: String,
            version: Int,
            id: String = UUID.randomUUID().toString(),
            sequence: Long? = null,
            payload: Payload = Payload(bytes),
        ): Envelope =
            Envelope(
                id = EventId(id),
                source = "/gst/payload-svc",
                type = "com.dynokore.gst.payload.PayloadReady.v1",
                subject = doc,
                time = Instant.parse("2026-10-19T08:00:00Z"),
                tenant = TenantId(tenant),
                entity = EntityRef("E-001"),
                docUuid = DocUuid(UUID.fromString(doc)),
                transactionDate = LocalDate.parse("2026-10-19"),
                docSequence = sequence,
                instanceId = "payload-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson =
                    """{"docUuid":"$doc","version":$version,"docsequence":${sequence ?: 1},"submissionType":"2",
                    |"specificationVersion":"1.0","payloadBytes":"${Base64.getEncoder().encodeToString(payload.bytes)}",
                    |"payloadSha256":"${payload.declaredSha}"}
                    """.trimMargin(),
            )
    }

    private class Payload(
        val bytes: ByteArray,
        val declaredSha: String = sha(bytes),
    )

    private fun run(
        dir: Path,
        block: Kit.() -> Unit,
    ) {
        SubmissionContext.online(dir).run { context -> Kit(context).block() }
    }

    @Test
    fun `INV-AC-1870 two event ids for one version run the handler twice and give one dispatch`(
        @TempDir dir: Path,
    ) = run(dir) {
        val doc = UUID.randomUUID().toString()
        val recording = Recording(handler)
        deliver(envelope(doc, 2, sequence = 1))
        deliver(envelope(doc, 2, sequence = 2))

        val result = consumer(recording).pollOnce()

        recording.outcomes shouldBe listOf(HandleOutcome.APPLIED, HandleOutcome.IGNORED)
        result shouldBe PollResult(applied = 1, ignored = 1, duplicates = 0, stale = 0, deadLettered = 0)
        submissionRows(doc, 2) shouldBe 1
        queueRows(doc) shouldBe 1
        dispatches() shouldBe listOf(doc to 2)
    }

    @Test
    fun `INV-AC-1870 one event id twice is discarded by the inbox before the handler runs`(
        @TempDir dir: Path,
    ) = run(dir) {
        val doc = UUID.randomUUID().toString()
        val recording = Recording(handler)
        val same = envelope(doc, 2, sequence = 1)
        deliver(same)
        deliver(same)

        val result = consumer(recording).pollOnce()

        recording.outcomes shouldBe listOf(HandleOutcome.APPLIED)
        result.applied shouldBe 1
        result.duplicates shouldBe 1
        submissionRows(doc, 2) shouldBe 1
        queueRows(doc) shouldBe 1
        dispatches() shouldBe listOf(doc to 2)
    }

    @Test
    fun `a PayloadReady for version 3 after version 2 inserts a second row and waits without an item`(
        @TempDir dir: Path,
    ) = run(dir) {
        val doc = UUID.randomUUID().toString()
        deliver(envelope(doc, 2, sequence = 1))
        deliver(envelope(doc, 3, sequence = 2))

        consumer(handler).pollOnce().applied shouldBe 2

        submissionRows(doc, 2) shouldBe 1
        submissionRows(doc, 3) shouldBe 1
        // INV-REQ-571: version 3 is READY and has no item while version 2 is in flight
        queueRows(doc) shouldBe 1
        dispatches() shouldBe listOf(doc to 2)
    }

    @Test
    fun `a payload with a wrong hash is dead-lettered and writes no submission row`(
        @TempDir dir: Path,
    ) = run(dir) {
        val doc = UUID.randomUUID().toString()
        deliver(envelope(doc, 2, payload = Payload(bytes, "0".repeat(SHA_LENGTH))))

        val result = consumer(handler).pollOnce()

        result.deadLettered shouldBe 1
        result.applied shouldBe 0
        submissionRows(doc, 2) shouldBe 0
        queueRows(doc) shouldBe 0
    }

    @Test
    fun `a second delivery of a stored version with another payload hash is dead-lettered and nothing is replaced`(
        @TempDir dir: Path,
    ) = run(dir) {
        val doc = UUID.randomUUID().toString()
        deliver(envelope(doc, 2, sequence = 1))
        deliver(envelope(doc, 2, sequence = 2, payload = Payload("<Other/>".toByteArray())))

        val result = consumer(handler).pollOnce()

        result.applied shouldBe 1
        result.deadLettered shouldBe 1
        queueRows(doc) shouldBe 1
        val stored =
            asTenant {
                dsl
                    .select(Submission.SUBMISSION_.PAYLOAD_SHA256)
                    .from(Submission.SUBMISSION_)
                    .where(Submission.SUBMISSION_.DOC_UUID.eq(doc))
                    .fetchOne(Submission.SUBMISSION_.PAYLOAD_SHA256)
            }
        stored shouldBe sha(bytes)
    }

    @Test
    fun `a forced exception after the insert rolls back the row, the item and the inbox row together`(
        @TempDir dir: Path,
    ) = run(dir) {
        val doc = UUID.randomUUID().toString()
        deliver(envelope(doc, 2, sequence = 1))
        val failing =
            Recording(handler) { throw IllegalStateException("forced after the insert") }

        val consumer = consumer(failing)
        shouldThrow<IllegalStateException> { consumer.pollOnce() }

        failing.outcomes shouldBe listOf(HandleOutcome.APPLIED)
        submissionRows(doc, 2) shouldBe 0
        queueRows(doc) shouldBe 0
        inboxRows() shouldBe 0
    }

    @Test
    fun `the handler has no dependency on a transport client or port`() {
        val types = PayloadReadyHandler::class.java.constructors.flatMap { it.parameterTypes.toList() }
        types.isEmpty() shouldBe false
        types.filter { it.name.contains("Transport") } shouldBe emptyList()
        PayloadReadyHandler::class.java.declaredFields
            .map { it.type.name }
            .filter { it.contains("Transport") } shouldBe
            emptyList()
    }

    @Test
    fun `the service declares one JdbcInboxConsumer for the PayloadReady topic and one for BreakGlassSessionChanged`(
        @TempDir dir: Path,
    ) = run(dir) {
        val consumers = context.getBeansOfType(JdbcInboxConsumer::class.java)
        consumers.keys shouldBe
            setOf(
                "payloadReadyConsumer",
                "breakGlassSessionConsumer",
                "periodExtendedConsumer",
                "canonicalVersionCreatedConsumer",
                "periodDueDateChangedConsumer",
                "periodRecordsChangedConsumer",
            )
        val factory = (context as ConfigurableApplicationContext).beanFactory
        consumers.keys.filter { factory.getBeanDefinition(it).isPrimary } shouldBe emptyList()
        context.getBeansOfType(ReplayDelivery::class.java).keys shouldBe setOf("submissionReplayDelivery")
    }

    private companion object {
        const val SHA_LENGTH = 64
    }
}

private fun sha(payload: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload))
