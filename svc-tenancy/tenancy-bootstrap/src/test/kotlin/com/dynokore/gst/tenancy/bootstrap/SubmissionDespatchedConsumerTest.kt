package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.TenancyEventCatalogue
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.DESPATCH_FACT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.INBOX
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/**
 * INV-AC-16774 through the service boundary: a `SubmissionDespatched` record in the event log is consumed by the inbox
 * consumer of [SubmissionDespatchedConsumerConfiguration] into `tenancy.despatch_fact`. Only the event-log read is a
 * list in memory: the `eventlog` schema belongs to the event-log instance.
 */
class SubmissionDespatchedConsumerTest {
    private val json = EnvelopeJson()

    private class Log : EventLogReader {
        private val records = mutableListOf<LogRecord>()

        fun add(envelopeJson: String) {
            records += LogRecord(OFFSETS.incrementAndGet(), envelopeJson)
        }

        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> =
            if (topic == TenancyEventCatalogue.SUBMISSION_DESPATCHED_TOPIC) {
                records.filter { it.offset > afterOffset }.take(limit)
            } else {
                emptyList()
            }

        private companion object {
            // the offset row of consumer tenancy-svc is platform-scoped and the database is shared by the tests
            val OFFSETS = AtomicLong(System.currentTimeMillis() * 1000)
        }
    }

    private class Env(
        val context: AssertableWebApplicationContext,
        val log: Log,
    ) {
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val tx: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val consumer: JdbcInboxConsumer = context.getBean("submissionDespatchedConsumer", JdbcInboxConsumer::class.java)

        fun <T> inTenant(
            tenant: TenantId,
            block: () -> T,
        ): T = tenants.bind(tenant, "user:test") { block() }

        fun facts(tenant: TenantId): List<String> =
            inTenant(tenant) {
                tx.inTransaction {
                    dsl
                        .selectFrom(DESPATCH_FACT)
                        .fetch()
                        .map { it.entityRef!! }
                }
            }

        fun inboxRows(tenant: TenantId): Int = inTenant(tenant) { tx.inTransaction { dsl.fetchCount(INBOX) } }
    }

    private fun run(block: (Env) -> Unit) {
        val log = Log()
        BoundaryFixture
            .runner()
            .withBean(EventLogReader::class.java, { log })
            .run { context ->
                context.startupFailure?.let { throw it }
                block(Env(context, log))
            }
    }

    private fun despatched(
        tenant: TenantId,
        eventId: String,
        entity: String,
    ): String =
        json.write(
            Envelope(
                id = EventId(eventId),
                source = "/gst/submission-svc",
                type = TenancyEventCatalogue.SUBMISSION_DESPATCHED_TYPE,
                subject = UUID.randomUUID().toString(),
                time = Instant.parse("2026-11-02T10:00:00Z"),
                tenant = tenant,
                entity = EventEntityRef(entity),
                docUuid = DocUuid(UUID.randomUUID()),
                transactionDate = null,
                docSequence = null,
                instanceId = "submission-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson = """{"docUuid":"x","version":1,"attemptNumber":1,"payloadSha256":"0"}""",
            ),
        )

    @Test
    fun `INV-AC-16774 the first event of an entity inserts its fact and a repeat event changes nothing`() {
        run { env ->
            val tenant = TenantId(UUID.randomUUID())
            env.log.add(despatched(tenant, "ev-${UUID.randomUUID()}", "ENT-1"))
            assertThat(env.consumer.pollOnce()).isEqualTo(PollResult(1, 0, 0, 0, 0))
            assertThat(env.facts(tenant)).containsExactly("ENT-1")
            env.log.add(despatched(tenant, "ev-${UUID.randomUUID()}", "ENT-1"))
            assertThat(env.consumer.pollOnce()).isEqualTo(PollResult(0, 1, 0, 0, 0))
            assertThat(env.facts(tenant)).containsExactly("ENT-1")
        }
    }

    @Test
    fun `INV-AC-16774 a redelivery of the same event id applies nothing twice`() {
        run { env ->
            val tenant = TenantId(UUID.randomUUID())
            val eventId = "ev-${UUID.randomUUID()}"
            env.log.add(despatched(tenant, eventId, "ENT-2"))
            assertThat(env.consumer.pollOnce()).isEqualTo(PollResult(1, 0, 0, 0, 0))
            env.log.add(despatched(tenant, eventId, "ENT-2"))
            assertThat(env.consumer.pollOnce()).isEqualTo(PollResult(0, 0, 1, 0, 0))
            assertThat(env.facts(tenant)).containsExactly("ENT-2")
            assertThat(env.inboxRows(tenant)).isEqualTo(1)
        }
    }

    @Test
    fun `INV-AC-16774 an event without tenantid goes to the dead letter and writes no fact`() {
        run { env ->
            val tenant = TenantId(UUID.randomUUID())
            val record = json.write(envelopeOf(tenant)).replace(Regex(",?\"tenantid\":\"[^\"]*\""), "")
            assertThat(record).doesNotContain("tenantid")
            env.log.add(record)
            assertThat(env.consumer.pollOnce()).isEqualTo(PollResult(0, 0, 0, 0, 1))
            assertThat(env.facts(tenant)).isEmpty()
        }
    }

    @Test
    fun `INV-AC-16774 an event without entityref goes to the dead letter and writes no fact`() {
        run { env ->
            val tenant = TenantId(UUID.randomUUID())
            val record = json.write(envelopeOf(tenant)).replace(Regex(",?\"entityref\":\"[^\"]*\""), "")
            assertThat(record).doesNotContain("entityref")
            env.log.add(record)
            assertThat(env.consumer.pollOnce()).isEqualTo(PollResult(0, 0, 0, 0, 1))
            assertThat(env.facts(tenant)).isEmpty()
        }
    }

    private fun envelopeOf(tenant: TenantId) =
        Envelope(
            id = EventId("ev-${UUID.randomUUID()}"),
            source = "/gst/submission-svc",
            type = TenancyEventCatalogue.SUBMISSION_DESPATCHED_TYPE,
            subject = UUID.randomUUID().toString(),
            time = Instant.parse("2026-11-02T10:00:00Z"),
            tenant = tenant,
            entity = EventEntityRef("ENT-3"),
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "submission-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = "{}",
        )
}
