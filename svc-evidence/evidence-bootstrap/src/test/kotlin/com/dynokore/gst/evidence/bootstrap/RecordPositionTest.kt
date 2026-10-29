package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.adapters.persistence.ChainWiring
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogPositions
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.composition.ServiceDataSources
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.jooq.DSLContext
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/**
 * INV-AC-5543 and INV-REQ-3650 b2-b4 through the real inbox consumers of the two record topics, the real event-log
 * reader and the event-log DDL of outbox-and-queue ([EventLogDb]) on PostgreSQL 16, 17 and 18, in a scratch database
 * per test (P109). The event log is filled and aged as the superuser; the purge is the sys-owned
 * `eventlog.purge_batch()` run by the purge login.
 */
class RecordPositionTest {
    private val audit = RecordConsumerConfiguration.AUDIT_ENTRY_TOPIC
    private val trace = RecordConsumerConfiguration.DECISION_TRACE_TOPIC
    private val doc = UUID.fromString("0b1f2c3d-0000-4000-8000-000000000003")
    private val json = EnvelopeJson()

    private class Env(
        val context: ApplicationContext,
        /** The application database: `evidence.*`. */
        val url: String,
        /** The event-log database: `eventlog.*`; the same as [url] when the event log shares the database. */
        val eventLogUrl: String = url,
    ) {
        val tenant = TenantId(UUID.randomUUID())
        val consumers: Map<String, InboxConsumer> = context.getBeansOfType(InboxConsumer::class.java)
        val auditConsumer: InboxConsumer = consumers.getValue("auditEntryConsumer")
        val traceConsumer: InboxConsumer = consumers.getValue("decisionTraceConsumer")
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)

        /** The consumer alone, as it runs when the process dies before it writes the position. */
        fun bare(
            topic: String,
            handler: com.dynokore.platform.outboxandqueue.EventHandler,
        ) = JdbcInboxConsumer(
            ConsumerSubscription(LocatorConsumerConfiguration.CONSUMER, topic, ChainWiring.SCHEMA),
            context.getBean(EventCatalogue::class.java),
            context.getBean(EventLogReader::class.java),
            scope,
            dsl,
            handler,
            RebuildRequiredSink { },
            Clock.systemUTC(),
        )

        fun <T> sql(
            query: String,
            vararg args: Any,
            on: String = url,
            read: (java.sql.ResultSet) -> T,
        ): T =
            EvidenceDb.superuserOf(on).use { c ->
                c.prepareStatement(query).use { s ->
                    args.forEachIndexed { i, a -> s.setObject(i + 1, a) }
                    s.executeQuery().use { rows ->
                        rows.next()
                        read(rows)
                    }
                }
            }

        fun execute(query: String) =
            EvidenceDb.superuserOf(eventLogUrl).use { c -> c.createStatement().use { it.execute(query) } }

        fun count(
            query: String,
            on: String = url,
        ): Long = sql(query, on = on) { it.getLong(1) }

        fun position(topic: String): Long =
            sql(
                "select position from eventlog.record_position where topic_key = ?",
                EventLogDb.key(topic),
                on = eventLogUrl,
            ) { it.getLong(1) }

        fun committed(topic: String): Long =
            sql(
                "select coalesce(max(next_offset), 1) - 1 from evidence.consumer_offset " +
                    "where consumer = ? and topic = ?",
                LocatorConsumerConfiguration.CONSUMER,
                topic,
            ) { it.getLong(1) }

        fun rows(topic: String): Long = count("select count(*) from eventlog.${EventLogDb.key(topic)}", eventLogUrl)

        /** The purge login's `eventlog.purge_batch()`; the rows it deleted on [topic]. */
        fun purge(topic: String): Long =
            DriverManager.getConnection(eventLogUrl, EventLogDb.PURGE, EventLogDb.PURGE_PASSWORD).use { c ->
                c.createStatement().use { s ->
                    val named = "purged_topic in ('$topic', '${EventLogDb.key(topic)}')"
                    s
                        .executeQuery(
                            "select coalesce(sum(purged_rows), 0) from eventlog.purge_batch() where $named",
                        ).use {
                            it.next()
                            it.getLong(1)
                        }
                }
            }
    }

    private fun run(
        dir: Path,
        version: EvidenceDb.Version,
        block: Env.() -> Unit,
    ) {
        EvidenceDb.withScratchDatabase(EventLogDb.setup(), version = version) { url ->
            EvidenceContext.online(dir).withPropertyValues("spring.datasource.url=$url").run { context ->
                Env(context, url).block()
            }
        }
    }

    @Suppress("LongParameterList") // the fields of the envelope a test varies
    private fun Env.emit(
        topic: String,
        type: String,
        eventId: String,
        docSequence: Long?,
        data: String,
        entity: String?,
    ) {
        val envelope =
            json.write(
                Envelope(
                    id = EventId(eventId),
                    source = "/gst/test",
                    type = type,
                    subject = doc.toString(),
                    time = Instant.parse("2026-10-19T10:00:00Z"),
                    tenant = tenant,
                    entity = entity?.let { EventEntityRef(it) } ?: EventEntityRef.TENANT_WIDE,
                    docUuid = null,
                    transactionDate = null,
                    docSequence = docSequence,
                    instanceId = "test-0",
                    correlation = CorrelationId("corr-1"),
                    causation = CausationId("cause-1"),
                    dataJson = data,
                ),
            )
        EvidenceDb.superuserOf(eventLogUrl).use { c ->
            c
                .prepareStatement(
                    "insert into eventlog.${EventLogDb.key(topic)} (tenant_id, event_id, subject, envelope) " +
                        "values (?, ?, ?, ?::jsonb)",
                ).use {
                    it.setObject(1, tenant.value)
                    it.setString(2, eventId)
                    it.setString(3, doc.toString())
                    it.setString(4, envelope)
                    it.execute()
                }
        }
    }

    private fun Env.emitAudit(eventId: String) =
        emit(
            audit,
            "com.dynokore.gst.platform.AuditEntryRecorded.v1",
            eventId,
            null,
            """{"scope":"invoice","action":"read","actor":"user-1"}""",
            "ENT-3F2A9C",
        )

    private fun Env.emitTrace(
        eventId: String,
        docSequence: Long,
    ) = emit(
        trace,
        "com.dynokore.gst.canonical.DecisionTraceRecorded.v1",
        eventId,
        docSequence,
        """{"docUuid":"$doc","version":1,"traceReference":"t","decisionTrace":{"rule":"$eventId"}}""",
        "ENT-3F2A9C",
    )

    private fun Env.stored(): Long =
        count("select count(*) from evidence.applied_record") + count("select count(*) from evidence.pending_record")

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `INV-AC-5543 a redelivered record event is stored once and the position never exceeds the committed offset`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = run(dir, version) {
        emitAudit("au-1")
        emitAudit("au-1") // redelivered
        emitAudit("au-2")
        val first = auditConsumer.pollOnce()
        first.applied shouldBe 2
        first.duplicates shouldBe 1
        stored() shouldBe 2
        committed(audit) shouldBe 3
        position(audit) shouldBe 3

        // a crash between the commit of au-3 and the write of its position: the consumer alone commits it
        emitAudit("au-3")
        bare(audit, ChainWiring.auditEntryHandler(dsl)).pollOnce().applied shouldBe 1
        committed(audit) shouldBe 4
        position(audit) shouldBe 3
        position(audit) shouldBeLessThanOrEqual committed(audit)

        // after the restart the next poll (nothing new to read) writes the committed offset; nothing was lost
        auditConsumer.pollOnce().total shouldBe 0
        position(audit) shouldBe 4
        stored() shouldBe 3

        emitAudit("au-3") // redelivered after the restart
        auditConsumer.pollOnce().duplicates shouldBe 1
        stored() shouldBe 3
        position(audit) shouldBeLessThanOrEqual committed(audit)

        emitTrace("dt-1", 1)
        traceConsumer.pollOnce().applied shouldBe 1
        position(trace) shouldBe committed(trace)
        position(audit) shouldBe committed(audit)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `a failed position write never blocks consumption and is retried after the next commit`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = run(dir, version) {
        val real = context.getBean(EventLogPositions::class.java)
        var failing = true
        val flaky =
            EventLogPositions {
                topic,
                offset,
                ->
                if (failing) error("event log unreachable") else real.record(topic, offset)
            }
        val meters = SimpleMeterRegistry()
        val consumer =
            PositionRecordingConsumer(
                bare(audit, ChainWiring.auditEntryHandler(dsl)),
                RecordPositionWriter(LocatorConsumerConfiguration.CONSUMER, audit, scope, dsl, flaky, meters),
            )

        fun gauge(name: String) =
            meters
                .get(name)
                .tag("topic", audit)
                .gauge()
                .value()
        emitAudit("au-1")
        consumer.pollOnce().applied shouldBe 1
        position(audit) shouldBe 0
        meters
            .get(RecordPositionWriter.FAILURES)
            .tag("topic", audit)
            .counter()
            .count() shouldBe 1.0
        gauge(RecordPositionWriter.COMMITTED) shouldBe 1.0
        gauge(RecordPositionWriter.POSITION) shouldBe 0.0
        gauge(RecordPositionWriter.GAP) shouldBe 1.0

        failing = false
        emitAudit("au-2")
        consumer.pollOnce().applied shouldBe 1
        position(audit) shouldBe 2
        gauge(RecordPositionWriter.COMMITTED) shouldBe 2.0
        gauge(RecordPositionWriter.POSITION) shouldBe 2.0
        gauge(RecordPositionWriter.GAP) shouldBe 0.0
        meters
            .get(RecordPositionWriter.FAILURES)
            .tag("topic", audit)
            .counter()
            .count() shouldBe 1.0
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `the runtime login writes nothing on eventlog but the position function`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = run(dir, version) {
        val tables =
            listOf("record_position", EventLogDb.key(audit), EventLogDb.key(trace), "retention", "purge_watermark")
        tables.forEach {
            count(
                "select count(*) from (select 1 where has_table_privilege('${EvidenceDb.RUNTIME}', 'eventlog.$it', " +
                    "'insert,update,delete,truncate')) p",
                eventLogUrl,
            ) shouldBe 0
        }
        count(
            "select count(*) from (select 1 where has_function_privilege('${EvidenceDb.RUNTIME}', " +
                "'eventlog.record_position(text, bigint)', 'execute')) p",
            eventLogUrl,
        ) shouldBe 1
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `an entry whose docsequence is older than one already held is still stored`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = run(dir, version) {
        emitTrace("dt-1", 5)
        emitTrace("dt-2", 2)
        traceConsumer.pollOnce().applied shouldBe 2
        stored() shouldBe 2
        position(trace) shouldBe 2
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `with evidence-svc stopped past retention nothing above the position is purged, all stored after restart`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = run(dir, version) {
        repeat(3) { emitAudit("au-$it") }
        execute("update eventlog.${EventLogDb.key(audit)} set inserted_at = now() - interval '100 days'")
        purge(audit) shouldBe 0 // position 0: evidence-svc has stored nothing
        rows(audit) shouldBe 3

        auditConsumer.pollOnce().applied shouldBe 3
        position(audit) shouldBe 3
        repeat(2) { emitAudit("au-late-$it") } // evidence-svc stopped: rows 4 and 5 arrive and age
        execute("update eventlog.${EventLogDb.key(audit)} set inserted_at = now() - interval '100 days'")
        purge(audit) shouldBe 3
        rows(audit) shouldBe 2
        count("select min(log_offset) from eventlog.${EventLogDb.key(audit)}", eventLogUrl) shouldBe 4

        auditConsumer.pollOnce().applied shouldBe 2 // after the restart every entry is stored
        stored() shouldBe 5
        position(audit) shouldBe 5
    }

    @Suppress("MaxLineLength", "ktlint:standard:max-line-length") // the name is fixed by the task text
    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(EvidenceDb.Version::class)
    fun `with gst db event-log-url naming another database the position is written there and the application database holds no eventlog schema`(
        version: EvidenceDb.Version,
        @TempDir dir: Path,
    ) = EvidenceDb.withScratchDatabase(EventLogDb.setup(), version = version) { eventLogUrl ->
        EvidenceDb.withScratchDatabase("", version = version) { appUrl ->
            (appUrl == eventLogUrl) shouldBe false
            EvidenceContext
                .online(dir)
                .withPropertyValues("spring.datasource.url=$appUrl", "gst.db.event-log-url=$eventLogUrl")
                .run { context ->
                    context.containsBean(ServiceDataSources.EVENT_LOG) shouldBe true
                    val env = Env(context, appUrl, eventLogUrl)
                    env.emitAudit("au-1")
                    env.auditConsumer.pollOnce().applied shouldBe 1
                    val committed = env.committed(audit)
                    env.position(audit) shouldBe committed
                    env.position(audit) shouldBeLessThanOrEqual committed
                    env.count(
                        "select count(*) from information_schema.schemata where schema_name = 'eventlog'",
                    ) shouldBe 0
                }
        }
    }
}
