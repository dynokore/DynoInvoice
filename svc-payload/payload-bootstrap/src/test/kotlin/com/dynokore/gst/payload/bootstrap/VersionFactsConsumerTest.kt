package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.events.CanonicalVersionCreatedHandler
import com.dynokore.gst.payload.adapters.persistence.JooqVersionFactsStore
import com.dynokore.gst.payload.adapters.persistence.jooq.tables.EventDeadLetter.Companion.EVENT_DEAD_LETTER
import com.dynokore.gst.payload.application.version.RecordVersionFacts
import com.dynokore.gst.payload.domain.version.VersionFacts
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
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-17179 through the real inbox consumer (`JdbcInboxConsumer`) on the inbox, offset and dead-letter tables of
 * schema `payload`, with the handler and the store of the TPL-18 context over PostgreSQL (P109). Only the event-log
 * read is a list in memory.
 */
class VersionFactsConsumerTest {
    private val actor = "svc:payload-svc"
    private val doc = UUID.fromString("0b1f2c3d-0000-4000-8000-000000000003")
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
        val store: JooqVersionFactsStore = context.getBean(JooqVersionFactsStore::class.java)
        val log = Log()
        val consumerName = VersionFactsConsumerConfiguration.CONSUMER + "-" + UUID.randomUUID()
        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription(
                    consumerName,
                    VersionFactsConsumerConfiguration.TOPIC,
                    VersionFactsConsumerConfiguration.SCHEMA,
                ),
                context.getBean(EventCatalogue::class.java),
                log,
                scope,
                dsl,
                CanonicalVersionCreatedHandler(RecordVersionFacts(store)),
                RebuildRequiredSink { },
                Clock.systemUTC(),
            )
    }

    private fun run(
        dir: Path,
        block: Boundary.(TenantId) -> Unit,
    ) {
        PayloadContext.online(dir).run { context -> Boundary(context).block(TenantId(UUID.randomUUID())) }
    }

    private fun <T> Boundary.tx(
        tenant: TenantId,
        body: () -> T,
    ): T = scope.bind(tenant, actor) { runner.inTransaction { body() } }

    private fun created(
        tenant: TenantId,
        eventId: String,
        entity: String,
        version: Int = 1,
        sgd: String? = null,
    ): String {
        val docField = """"docUuid":"$doc","""
        val amounts = if (sgd == null) """"totalIncludingTax":"80.00"""" else """"sgdTotalIncludingTax":"$sgd""""
        val envelope =
            Envelope(
                id = EventId(eventId),
                source = "/gst/canonical-svc",
                type = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1",
                subject = doc.toString(),
                time = Instant.parse("2026-10-20T10:00:00Z"),
                tenant = tenant,
                entity = EntityRef(entity),
                docUuid = DocUuid(doc),
                transactionDate = LocalDate.of(2026, 1, 15),
                docSequence = eventId.removePrefix("ev-").toLong(), // the order the events are consumed in
                instanceId = "canonical-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson =
                    """{$docField"version":$version,"canonicalDocument":{"currency":{"value":"USD"},""" +
                        """"totals":{"value":{$amounts}}}}""",
            )
        return json.write(envelope)
    }

    private fun Boundary.facts(
        tenant: TenantId,
        version: Int,
    ): VersionFacts? = tx(tenant) { store.find(doc, version) }

    @Test
    fun `INV-AC-17179 a version event stores its entity and SGD total once for its tenant`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        val t2 = TenantId(UUID.randomUUID())
        log.add(created(t1, "ev-1", "E-1", sgd = "1000.00"))
        log.add(created(t1, "ev-1", "E-1", sgd = "1000.00")) // the same event delivered again
        log.add(created(t1, "ev-2", "E-2", sgd = "5.00")) // another event for the same version
        log.add(created(t1, "ev-3", "E-1", version = 2)) // USD, no SGD total
        log.add(created(t1, "ev-4", "E-1", version = 3, sgd = "1.00").replace("\"docUuid\":\"$doc\",", ""))
        consumer.pollOnce() shouldBe PollResult(applied = 2, ignored = 1, duplicates = 1, stale = 0, deadLettered = 1)
        facts(t1, 1) shouldBe VersionFacts("E-1", BigDecimal("1000.00"))
        facts(t1, 2) shouldBe null
        facts(t2, 1) shouldBe null
        facts(t1, 3) shouldBe null
        tx(t1) { dsl.fetchCount(EVENT_DEAD_LETTER, EVENT_DEAD_LETTER.CONSUMER.eq(consumerName)) } shouldBe 1
    }
}
