package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.notification.adapters.NotificationDb
import com.dynokore.gst.notification.adapters.events.BacklogValuesMeasuredHandler
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.Alert.Companion.ALERT
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.EventDeadLetter.Companion.EVENT_DEAD_LETTER
import com.dynokore.gst.notification.adapters.persistence.jooq.tables.Inbox.Companion.INBOX
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
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

/**
 * INV-AC-20820 through the real inbox consumer (`JdbcInboxConsumer`) with the handler, the use case and the jOOQ
 * adapters of the TPL-18 context over PostgreSQL (P109). Only the event-log read is a list in memory: the `eventlog`
 * schema belongs to the event-log instance.
 */
class BacklogConsumerBoundaryTest {
    private val actor = "svc:notification-svc"
    private val at = Instant.parse("2026-11-12T10:00:00Z")
    private val json = EnvelopeJson()
    private var sequence = 0L

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
        topic: String,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val log = Log()

        // a consumer name of its own per test: the offset row is platform-scoped and the database is shared
        val consumerName = BacklogConsumerConfiguration.CONSUMER + "-" + UUID.randomUUID()
        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription(consumerName, topic, BacklogConsumerConfiguration.SCHEMA),
                context.getBean(EventCatalogue::class.java),
                log,
                scope,
                dsl,
                context.getBean(BacklogValuesMeasuredHandler::class.java),
                RebuildRequiredSink { },
                Clock.systemUTC(),
            )
    }

    private fun run(
        dir: Path,
        topic: String = BacklogConsumerConfiguration.TOPIC,
        block: Boundary.(TenantId) -> Unit,
    ) {
        NotificationContext.online(dir).run { context -> Boundary(context, topic).block(TenantId(UUID.randomUUID())) }
    }

    private fun <T> Boundary.tx(
        tenant: TenantId,
        body: () -> T,
    ): T = scope.bind(tenant, actor) { runner.inTransaction { body() } }

    private fun policy(
        tenant: TenantId,
        entity: String,
    ) = NotificationDb.superuser().use {
        it.createStatement().use { s ->
            s.execute(
                "insert into notification.alert_policy (tenant_id, entity_ref, alert_class, recipients, channels, " +
                    "updated_at, max_queue_depth) values ('${tenant.value}', '$entity', 'BACKLOG', '[]', '[]', now(), 10)",
            )
        }
    }

    @Suppress("LongParameterList")
    private fun measured(
        tenant: TenantId,
        eventId: String,
        entity: String = "E-1",
        period: String = "2026-Q1",
        queueDepth: Int = 11,
        failureRate: Double = 0.1,
        type: String = "com.dynokore.gst.submission.BacklogValuesMeasured.v1",
    ): String =
        json.write(
            Envelope(
                id = EventId(eventId),
                source = "/gst/submission-svc",
                type = type,
                subject = entity,
                time = at,
                tenant = tenant,
                entity = EntityRef(entity),
                docUuid = DocUuid(UUID.randomUUID()),
                transactionDate = LocalDate.of(2026, 1, 15),
                docSequence = ++sequence,
                instanceId = "submission-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson =
                    """{"entityRef":"$entity","periodRef":"$period","measuredAt":"2026-11-12T09:59:00Z",""" +
                        """"queueDepth":$queueDepth,"oldestUnsentAgeSeconds":5,"failureRate":$failureRate,""" +
                        """"unacknowledgedCount":0}""",
            ),
        )

    private fun Boundary.alerts(
        tenant: TenantId,
        entity: String? = null,
    ): List<List<String?>> =
        tx(tenant) {
            dsl
                .selectFrom(ALERT)
                .fetch()
                .filter { entity == null || it.entityRef == entity }
                .map { listOf(it.state, it.alertClass, it.trigger, it.entityRef, it.periodRef) }
        }

    private fun Boundary.inboxRows(tenant: TenantId) = tx(tenant) { dsl.fetchCount(INBOX, INBOX.CONSUMER.eq(consumerName)) }

    private val raised = listOf("RAISED", "BACKLOG", "BACKLOG_THRESHOLD", "E-1", "2026-Q1")

    @Test
    fun `INV-AC-20820 the first event over the threshold raises one alert and writes one inbox row`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        policy(tenant, "E-1")
        log.add(measured(tenant, "ev-1"))
        consumer.pollOnce() shouldBe PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
        alerts(tenant) shouldBe listOf(raised)
        inboxRows(tenant) shouldBe 1
    }

    @Test
    fun `INV-AC-20820 the repeated event writes nothing more`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        policy(tenant, "E-1")
        log.add(measured(tenant, "ev-1"))
        consumer.pollOnce().applied shouldBe 1
        log.add(measured(tenant, "ev-1"))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 1, stale = 0, deadLettered = 0)
        alerts(tenant) shouldBe listOf(raised)
        inboxRows(tenant) shouldBe 1
    }

    @Test
    fun `INV-AC-20820 a second event id for the same entity and period writes no second alert`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        policy(tenant, "E-1")
        log.add(measured(tenant, "ev-1"))
        log.add(measured(tenant, "ev-2", queueDepth = 12))
        val result = consumer.pollOnce()
        result.applied shouldBe 1
        result.ignored shouldBe 1
        alerts(tenant) shouldBe listOf(raised)
        inboxRows(tenant) shouldBe 2
    }

    @Test
    fun `INV-AC-20820 a queue depth equal to the threshold writes no alert`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        policy(tenant, "E-1")
        log.add(measured(tenant, "ev-3", period = "2026-Q2", queueDepth = 10))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 1, duplicates = 0, stale = 0, deadLettered = 0)
        alerts(tenant) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20820 an entity with no policy row writes no alert and is NOT_CONFIGURED`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        policy(tenant, "E-1")
        log.add(measured(tenant, "ev-4", entity = "E-2"))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 1, duplicates = 0, stale = 0, deadLettered = 0)
        alerts(tenant) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20820 a failure rate of 1_5 goes to the dead letter with its reason and writes no alert`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        policy(tenant, "E-1")
        log.add(measured(tenant, "ev-5", failureRate = 1.5))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)
        alerts(tenant) shouldBe emptyList()
        tx(tenant) {
            dsl
                .select(EVENT_DEAD_LETTER.REASON)
                .from(EVENT_DEAD_LETTER)
                .where(EVENT_DEAD_LETTER.CONSUMER.eq(consumerName))
                .fetch(0)
        } shouldBe listOf("failureRate must be within 0 to 1")
        consumer.pollOnce().total shouldBe 0
    }

    @Test
    fun `INV-AC-20820 a look-alike event type is refused to the dead letter`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        policy(tenant, "E-1")
        log.add(measured(tenant, "ev-6", type = "com.dynokore.gst.submission.BacklogValuesMeasured.v2"))
        consumer.pollOnce().applied shouldBe 0
        alerts(tenant) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20820 the whole sequence of the criterion in one consumer`(
        @TempDir dir: Path,
    ) = run(dir) { tenant ->
        policy(tenant, "E-1")
        log.add(measured(tenant, "ev-1"))
        log.add(measured(tenant, "ev-1"))
        log.add(measured(tenant, "ev-2", queueDepth = 12))
        log.add(measured(tenant, "ev-3", period = "2026-Q2", queueDepth = 10))
        log.add(measured(tenant, "ev-4", entity = "E-2"))
        log.add(measured(tenant, "ev-5", failureRate = 1.5))
        consumer.pollOnce() shouldBe PollResult(applied = 1, ignored = 3, duplicates = 1, stale = 0, deadLettered = 1)
        alerts(tenant) shouldBe listOf(raised)
    }
}
