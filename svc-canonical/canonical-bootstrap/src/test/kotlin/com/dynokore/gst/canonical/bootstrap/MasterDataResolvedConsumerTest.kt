package com.dynokore.gst.canonical.bootstrap

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.gst.canonical.adapters.CanonicalDb
import com.dynokore.gst.canonical.adapters.events.MasterDataResolvedHandler
import com.dynokore.gst.canonical.adapters.events.OutboxReleasedEventPublisher
import com.dynokore.gst.canonical.adapters.persistence.JooqDocumentReleaseStore
import com.dynokore.gst.canonical.application.document.ReleaseMasterDataHold
import com.dynokore.gst.canonical.application.document.ReleasedEventPublisher
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
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-21250 through the real inbox consumer (`JdbcInboxConsumer`) on the inbox, offset and dead-letter tables of
 * schema `canonical`, with the handler, the use case, the stores and the outbox publisher of the TPL-18 context over
 * PostgreSQL (P109). Only the event-log read is a list in memory.
 */
class MasterDataResolvedConsumerTest {
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
        val context: ApplicationContext,
        topic: String,
    ) {
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val log = Log()
        val consumerName = MasterDataResolvedConsumerConfiguration.CONSUMER + "-" + UUID.randomUUID()
        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription(consumerName, topic, MasterDataResolvedConsumerConfiguration.SCHEMA),
                context.getBean(EventCatalogue::class.java),
                log,
                context.getBean(TenantScope::class.java),
                dsl,
                context.getBean("masterDataResolvedHandler", EventHandler::class.java),
                RebuildRequiredSink { },
                Clock.systemUTC(),
            )
    }

    private fun run(
        dir: Path,
        topic: String = MasterDataResolvedConsumerConfiguration.TOPIC,
        block: Boundary.() -> Unit,
    ) {
        CanonicalContext.online(dir).run { context -> Boundary(context, topic).block() }
    }

    private fun insert(
        tenant: UUID,
        state: String,
        reasons: String?,
    ): UUID {
        val doc = UUID.randomUUID()
        CanonicalDb.superuser().use {
            it.createStatement().use { s ->
                s.execute(
                    "insert into canonical.document (doc_uuid, tenant_id, entity_id, direction, document_type_code, " +
                        "document_number, issuer_party_key, fiscal_year, partition_date, transaction_date, " +
                        "current_version, state, entity_ref, document_date, prescribed_period_ref, hold_reasons) " +
                        "values ('$doc', '$tenant', '${UUID.randomUUID()}', 'SALE', 380, 'N-$doc', 'issuer', 2026, " +
                        "'2026-03-25', '2026-03-25', 1, '$state', 'E-001', '2026-03-20', '2026-Q1', " +
                        "${reasons?.let { "'$it'" } ?: "null"})",
                )
            }
        }
        return doc
    }

    /** The owner role reads the row, so row security hides nothing. */
    private fun ownerRows(sql: String): List<List<String?>> =
        CanonicalDb.superuser().use {
            it.createStatement().use { s ->
                s.executeQuery(sql).use { r ->
                    val width = r.metaData.columnCount
                    buildList { while (r.next()) add((1..width).map { c -> r.getString(c) }) }
                }
            }
        }

    private fun stateOf(doc: UUID) =
        ownerRows("select state, hold_reasons::text from canonical.document where doc_uuid = '$doc'").single()

    private fun releasedRows(docs: Collection<UUID>) =
        ownerRows(
            "select doc_uuid::text, data->'releasedHoldReasons', causation_id, correlation_id from canonical.outbox " +
                "where type = 'com.dynokore.gst.canonical.DocumentReleased.v1' and doc_uuid in " +
                docs.joinToString(",", "(", ")") { "'$it'" },
        )

    private fun envelope(
        tenant: UUID,
        eventId: String,
        data: String,
    ) = json.write(
        Envelope(
            id = EventId(eventId),
            source = "/gst/masterdata-svc",
            type = "com.dynokore.gst.masterdata.MasterDataResolved.v1",
            subject = "party-1",
            time = Instant.parse("2026-11-12T10:00:00Z"),
            tenant = TenantId(tenant),
            entity = EntityRef("E-001"),
            docUuid = DocUuid(UUID.randomUUID()),
            transactionDate = LocalDate.of(2026, 3, 20),
            docSequence = eventId.removePrefix("ev-").toLong(),
            instanceId = "masterdata-svc-0",
            correlation = CorrelationId("corr-$eventId"),
            causation = CausationId("cause-$eventId"),
            dataJson = data,
        ),
    )

    private fun data(docUuids: String?) =
        """{"partyRef":"P-1","resolvedIdentifiers":[]${docUuids?.let { ""","docUuids":$it""" }.orEmpty()}}"""

    private fun Boundary.deadLetterIds(): List<String> =
        ownerRows("select raw from canonical.event_dead_letter where consumer = '$consumerName' order by id")
            .map { row -> Regex("\"id\":\"(ev-\\d+)\"").find(row[0]!!)!!.groupValues[1] }

    private fun capturedLog(body: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(MasterDataResolvedHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            body()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.map { it.formattedMessage }
    }

    @Test
    fun `INV-AC-21250 the consumer subscription equals canonical-svc, the topic and schema canonical`(
        @TempDir dir: Path,
    ) = run(dir) {
        // The bean the application context builds, not the constants: a wrong topic wired into the bean fails here.
        val bean = context.getBean("masterDataResolvedConsumer", JdbcInboxConsumer::class.java)
        val field = JdbcInboxConsumer::class.java.getDeclaredField("subscription")
        field.isAccessible = true
        field.get(bean) shouldBe
            ConsumerSubscription("canonical-svc", "gst.masterdata.master-data-resolved.v1", "canonical")
    }

    @Test
    fun `INV-AC-21250 the consumer releases the named master-data holds once and dead-letters a malformed docUuids`(
        @TempDir dir: Path,
    ) = run(dir) {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        val d1 = insert(t1, "HELD", "{MDM-001}")
        val d2 = insert(t1, "HELD", "{MDM-001,ING-016}")
        val d3 = insert(t1, "INGESTED", null)
        val d4 = insert(t1, "HELD", "{MDM-001}")
        val d5 = insert(t2, "HELD", "{MDM-001}")
        val first = envelope(t1, "ev-1", data("""["$d1","$d2","$d3","$d5"]"""))
        log.add(first)
        log.add(first) // the same event id delivered again
        log.add(envelope(t1, "ev-2", data(null)))
        log.add(envelope(t1, "ev-3", data("[]")))
        log.add(envelope(t1, "ev-4", data("""["not-a-uuid"]""")))
        log.add(envelope(t1, "ev-5", data("\"$d4\"")))

        val lines =
            capturedLog {
                consumer.pollOnce() shouldBe
                    PollResult(applied = 1, ignored = 2, duplicates = 1, stale = 0, deadLettered = 2)
            }

        stateOf(d1) shouldBe listOf("INGESTED", null)
        val released = releasedRows(listOf(d1, d2, d3, d4, d5))
        released.size shouldBe 1
        released.single().take(2) shouldBe listOf(d1.toString(), """["MDM-001"]""")
        released.single()[2] shouldBe "ev-1"
        released.single()[3] shouldBe "corr-ev-1"
        stateOf(d2) shouldBe listOf("HELD", "{MDM-001,ING-016}")
        stateOf(d3) shouldBe listOf("INGESTED", null)
        stateOf(d4) shouldBe listOf("HELD", "{MDM-001}")
        stateOf(d5) shouldBe listOf("HELD", "{MDM-001}")
        lines shouldBe listOf("master data resolved: released 1, not-held 1, not-master-data 1, not-found 1")
        deadLetterIds() shouldBe listOf("ev-4", "ev-5")
        ownerRows("select count(*) from canonical.inbox where consumer = '$consumerName'")
            .single()
            .single() shouldBe "3"
    }

    @Test
    fun `INV-AC-21250 a failure after the stage rolls the release back and the record stays unconsumed`(
        @TempDir dir: Path,
    ) {
        CanonicalContext.online(dir).run { context ->
            val b = Boundary(context, MasterDataResolvedConsumerConfiguration.TOPIC)
            val t1 = UUID.randomUUID()
            val d1 = insert(t1, "HELD", "{MDM-001}")
            val real = context.getBean(OutboxReleasedEventPublisher::class.java)
            val failing =
                MasterDataResolvedHandler(
                    ReleaseMasterDataHold(
                        context.getBean(JooqDocumentReleaseStore::class.java),
                        ReleasedEventPublisher { event ->
                            real.stage(event)
                            error("write after the stage fails")
                        },
                        context.getBean(TransactionRunner::class.java),
                    ),
                )
            val consumer =
                JdbcInboxConsumer(
                    ConsumerSubscription(b.consumerName, MasterDataResolvedConsumerConfiguration.TOPIC, "canonical"),
                    context.getBean(EventCatalogue::class.java),
                    b.log,
                    context.getBean(TenantScope::class.java),
                    b.dsl,
                    InboundTraceHandler(failing, context.getBean(InboundEventTrace::class.java)),
                    RebuildRequiredSink { },
                    Clock.systemUTC(),
                )
            b.log.add(envelope(t1, "ev-1", data("""["$d1"]""")))
            runCatching { consumer.pollOnce() }.exceptionOrNull()?.message shouldBe "write after the stage fails"
            stateOf(d1) shouldBe listOf("HELD", "{MDM-001}")
            releasedRows(listOf(d1)).size shouldBe 0
            ownerRows("select count(*) from canonical.inbox where consumer = '${b.consumerName}'")
                .single()
                .single() shouldBe "0"
        }
    }
}
