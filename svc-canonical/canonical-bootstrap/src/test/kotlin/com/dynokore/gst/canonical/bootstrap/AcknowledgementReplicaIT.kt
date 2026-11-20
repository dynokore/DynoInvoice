package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.SignedBindings
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
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
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * INV-AC-25748: the SubmissionAcknowledged replica through the real inbox consumer (real handler bean, real tables,
 * real row-level security) on the topic `gst.submission.submission-acknowledged.v1`.
 */
class AcknowledgementReplicaIT {
    private val actor = SignedBindings.ACTOR
    private val envelopes = EnvelopeJson()
    private val t1 = TenantId(UUID.randomUUID())
    private val t2 = TenantId(UUID.randomUUID())
    private val d1 = UUID.randomUUID()
    private val s1 = "2026-11-10T08:15:30Z"

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

    private inner class Harness(
        val context: ApplicationContext,
        topic: String = AcknowledgementReplicaConfiguration.TOPIC,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val dataSource: DataSource = context.getBean(DataSource::class.java)
        val log = Log()
        val consumerName = AcknowledgementReplicaConfiguration.CONSUMER + "-" + UUID.randomUUID()
        private var events = 0

        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription(consumerName, topic, AcknowledgementReplicaConfiguration.SCHEMA),
                context.getBean(EventCatalogue::class.java),
                log,
                scope,
                dsl,
                context.getBean("submissionAcknowledgedHandler", EventHandler::class.java),
                RebuildRequiredSink { },
                Clock.systemUTC(),
            )

        /** [eventId] repeats an earlier event id to deliver the same event again. */
        fun send(
            data: String,
            tenant: TenantId = t1,
            eventId: String = "ev-${++events}",
        ) {
            log.add(
                envelopes.write(
                    Envelope(
                        id = EventId(eventId),
                        source = "/gst/submission-svc",
                        type = AcknowledgementReplicaConfiguration.EVENT_TYPE,
                        subject = d1.toString(),
                        time = Instant.parse("2026-11-12T10:00:00Z"),
                        tenant = tenant,
                        entity = if (tenant.isPlatform) EntityRef.PLATFORM else EntityRef("ENT-1"),
                        docUuid = DocUuid(d1),
                        transactionDate = null,
                        docSequence = null,
                        instanceId = "submission-svc-0",
                        correlation = CorrelationId("corr-$eventId"),
                        causation = CausationId("cause-$eventId"),
                        dataJson = data,
                    ),
                ),
            )
        }

        fun poll(): PollResult = consumer.pollOnce()

        /** The rows (doc, version, acknowledgement id, acknowledged_at, tenant) of the table read as [tenant]. */
        fun readAs(tenant: TenantId): List<List<String?>> =
            scope.bind(tenant, actor) {
                runner.inTransaction {
                    // plain JDBC on the transaction's connection: the row-level policy alone decides what is read
                    dsl.connectionResult { c ->
                        c.createStatement().use { st ->
                            st
                                .executeQuery(
                                    "select doc_uuid, version, acknowledgement_id, " +
                                        "to_char(acknowledged_at at time zone 'UTC', " +
                                        "'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'), " +
                                        "tenant_id from canonical.submission_acknowledgement " +
                                        "order by doc_uuid, version",
                                ).use { r ->
                                    buildList { while (r.next()) add((1..5).map { i -> r.getString(i) }) }
                                }
                        }
                    }
                }
            }

        fun readUnbound(): Int =
            dataSource.connection.use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("select count(*) from canonical.submission_acknowledgement").use {
                        it.next()
                        it.getInt(1)
                    }
                }
            }

        fun total(): Int =
            SampleExchangeKit
                .rows("select count(*) from canonical.submission_acknowledgement where doc_uuid = '$d1'")
                .single()
                .single()!!
                .toInt()

        fun deadLetters(): List<String> =
            SampleExchangeKit
                .rows("select reason from canonical.event_dead_letter where consumer = '$consumerName' order by id")
                .map { it[0]!! }
    }

    private fun run(
        dir: Path,
        topic: String = AcknowledgementReplicaConfiguration.TOPIC,
        block: Harness.() -> Unit,
    ) {
        CanonicalContext.online(dir).run { Harness(it, topic).block() }
    }

    private fun data(
        docUuid: String? = "\"$d1\"",
        version: String? = "2",
        id: String? = "\"A1\"",
        timestamp: String? = "\"$s1\"",
    ): String =
        listOfNotNull(
            docUuid?.let { "\"docUuid\":$it" },
            version?.let { "\"version\":$it" },
            id?.let { "\"acknowledgementId\":$it" },
            timestamp?.let { "\"timestamp\":$it" },
        ).joinToString(",", "{", "}")

    @Test
    fun `INV-AC-25748 the scenario stores one row per tenant, doc and version and refuses the malformed events`(
        @TempDir dir: Path,
    ) = run(dir) {
        send(data())
        send(data(id = "\"A2\"", timestamp = "\"2026-11-11T00:00:00Z\""), tenant = t2)
        send(data(version = "0"))
        send(data(id = null))
        send(data(docUuid = "\"not-a-uuid\""))
        poll() shouldBe PollResult(applied = 2, ignored = 0, duplicates = 0, stale = 0, deadLettered = 3)
        // the event of T1 for (D1, 2) delivered a second time (same event id: a redelivery of the first)
        send(data(), eventId = "ev-1")
        poll() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 1, stale = 0, deadLettered = 0)

        readAs(t1) shouldBe listOf(listOf("$d1", "2", "A1", s1, "${t1.value}"))
        readAs(t2) shouldBe listOf(listOf("$d1", "2", "A2", "2026-11-11T00:00:00Z", "${t2.value}"))
        readUnbound() shouldBe 0
        total() shouldBe 2
        deadLetters().size shouldBe 3
    }

    @Test
    fun `INV-AC-25748 the same event under a new event id is ignored and the first acknowledgement stays`(
        @TempDir dir: Path,
    ) = run(dir) {
        send(data())
        poll() shouldBe PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
        send(data(id = "\"A9\"", timestamp = "\"2027-01-01T00:00:00Z\""))
        poll() shouldBe PollResult(applied = 0, ignored = 1, duplicates = 0, stale = 0, deadLettered = 0)
        readAs(t1) shouldBe listOf(listOf("$d1", "2", "A1", s1, "${t1.value}"))
    }

    @Test
    fun `INV-AC-25748 acknowledged_at is the payload timestamp, not the receipt clock`(
        @TempDir dir: Path,
    ) = run(dir) {
        send(data(timestamp = "\"2026-11-10T16:15:30+08:00\""))
        poll()
        readAs(t1).single()[3] shouldBe "2026-11-10T08:15:30Z"
    }

    @Test
    fun `INV-AC-25748 other versions of the same document are their own rows`(
        @TempDir dir: Path,
    ) = run(dir) {
        send(data())
        send(data(version = "3", id = "\"A3\""))
        poll() shouldBe PollResult(applied = 2, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
        readAs(t1).map { it[1] } shouldBe listOf("2", "3")
    }

    @Test
    fun `INV-AC-25748 a payload that breaks the contract is dead lettered at once and adds no row`(
        @TempDir dir: Path,
    ) = run(dir) {
        val bad =
            listOf(
                data(version = "0"),
                data(version = "-1"),
                data(version = "1.5"),
                data(version = "\"2\""),
                data(version = null),
                data(id = null),
                data(id = "\"\""),
                data(id = "\"   \""),
                data(id = "7"),
                data(docUuid = "\"not-a-uuid\""),
                data(docUuid = "\"1-1-1-1-1\""),
                data(docUuid = null),
                data(timestamp = "\"yesterday\""),
                data(timestamp = "\"2026-11-10\""),
                data(timestamp = null),
                "[]",
            )
        bad.forEach { send(it) }
        poll() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = bad.size)
        total() shouldBe 0
        deadLetters().size shouldBe bad.size
    }

    @Test
    fun `INV-AC-25748 an event of the platform tenant adds no row`(
        @TempDir dir: Path,
    ) = run(dir) {
        // The envelope type refuses a tenantless acknowledgement before any handler sees it (FR-K.3, INV-Q-3018)...
        assertThrows<IllegalArgumentException> { send(data(), tenant = TenantId.PLATFORM) }
        // ...and the handler itself refuses a platform-tenant envelope (built with a platform event type).
        val handler = context.getBean("submissionAcknowledgedHandler", EventHandler::class.java)
        val platform =
            Envelope(
                id = EventId("ev-platform"),
                source = "/gst/rules-svc",
                type = ScopePackFeed.STAGED_EVENT_TYPE,
                subject = "x",
                time = Instant.parse("2026-11-12T10:00:00Z"),
                tenant = TenantId.PLATFORM,
                entity = EntityRef.PLATFORM,
                docUuid = null,
                transactionDate = null,
                docSequence = null,
                instanceId = "rules-svc-0",
                correlation = CorrelationId("c"),
                causation = CausationId("k"),
                dataJson = data(),
            )
        assertThrows<ContractViolation> { scope.bind(t1, actor) { runner.inTransaction { handler.handle(platform) } } }
        total() shouldBe 0
        readUnbound() shouldBe 0
    }

    @Test
    fun `INV-AC-25748 the consumer subscribes to the topic of the acknowledgement, under its own name`() {
        AcknowledgementReplicaConfiguration.TOPIC shouldBe "gst.submission.submission-acknowledged.v1"
        AcknowledgementReplicaConfiguration.EVENT_TYPE shouldBe "com.dynokore.gst.submission.SubmissionAcknowledged.v1"
        AcknowledgementReplicaConfiguration.CONSUMER shouldBe "canonical-svc-submission-acknowledged"
    }

    @Test
    fun `INV-AC-25748 the runtime role can select and insert but neither update nor delete`(
        @TempDir dir: Path,
    ) = run(dir) {
        fun priv(p: String) =
            SampleExchangeKit
                .rows("select has_table_privilege('canonical_runtime', 'canonical.submission_acknowledgement', '$p')")
                .single()
                .single()
        priv("SELECT") shouldBe "t"
        priv("INSERT") shouldBe "t"
        priv("UPDATE") shouldBe "f"
        priv("DELETE") shouldBe "f"
    }
}
