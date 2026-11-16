package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.PayloadDb
import com.dynokore.gst.payload.adapters.events.CanonicalVersionCreatedHandler
import com.dynokore.gst.payload.adapters.persistence.JooqBuildJobs
import com.dynokore.gst.payload.adapters.persistence.JooqCanonicalInputStore
import com.dynokore.gst.payload.adapters.persistence.JooqTypeDecisionStore
import com.dynokore.gst.payload.adapters.persistence.JooqVersionFactsStore
import com.dynokore.gst.payload.adapters.persistence.jooq.tables.EventDeadLetter.Companion.EVENT_DEAD_LETTER
import com.dynokore.gst.payload.application.build.BuildTrigger
import com.dynokore.gst.payload.application.version.RecordVersionFacts
import com.dynokore.gst.payload.domain.version.VersionFacts
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * INV-AC-22936 through the real inbox consumer on the tables of schema `payload` over PostgreSQL (P109). Only the
 * event-log read is a list in memory.
 */
class CanonicalInputConsumerTest {
    private val actor = "svc:payload-svc"
    private val d1 = UUID.fromString("0b1f2c3d-0000-4000-8000-000000000021")
    private val d2 = UUID.fromString("0b1f2c3d-0000-4000-8000-000000000022")
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
        val decisions: JooqTypeDecisionStore = context.getBean(JooqTypeDecisionStore::class.java)
        val facts: JooqVersionFactsStore = context.getBean(JooqVersionFactsStore::class.java)
        val inputs: JooqCanonicalInputStore = context.getBean(JooqCanonicalInputStore::class.java)
        val jobs: JooqBuildJobs = context.getBean(JooqBuildJobs::class.java)
        val trigger: BuildTrigger = context.getBean(BuildTrigger::class.java)
        val log = Log()
        val consumerName = VersionFactsConsumerConfiguration.CONSUMER + "-" + UUID.randomUUID()
        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription(
                    consumerName,
                    VersionFactsConsumerConfiguration.TOPIC,
                    VersionFactsConsumerConfiguration.SCHEMA,
                ),
                context.getBean(com.dynokore.platform.outboxandqueue.EventCatalogue::class.java),
                log,
                scope,
                dsl,
                CanonicalVersionCreatedHandler(RecordVersionFacts(facts), inputs, trigger),
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
        doc: UUID,
        document: String = """{"currency":{"value":"SGD"},"totals":{"value":{"totalIncludingTax":"80.00"}}}""",
        routing: String? = null,
    ): String {
        val routingField = routing?.let { ""","routingFacts":$it""" }.orEmpty()
        val envelope =
            Envelope(
                id = EventId(eventId),
                source = "/gst/canonical-svc",
                type = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1",
                subject = doc.toString(),
                time = Instant.parse("2026-10-20T10:00:00Z"),
                tenant = tenant,
                entity = EntityRef("E-1"),
                docUuid = DocUuid(doc),
                transactionDate = LocalDate.of(2026, 1, 15),
                docSequence = eventId.removePrefix("ev-").toLong(),
                instanceId = "canonical-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson = """{"docUuid":"$doc","version":1,"canonicalDocument":$document$routingField}""",
            )
        return json.write(envelope)
    }

    private class Row(
        val document: String,
        val direction: String?,
        val onNetwork: Boolean?,
    )

    private fun row(
        tenant: TenantId,
        doc: UUID,
    ): Row? =
        PayloadDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select canonical_document::text, direction, counterparty_on_network " +
                        "from payload.canonical_input where tenant_id = ? and doc_uuid = ?",
                ).use { st ->
                    st.setObject(1, tenant.value)
                    st.setObject(2, doc)
                    st.executeQuery().use { rs ->
                        if (!rs.next()) {
                            null
                        } else {
                            Row(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getObject(3) as Boolean?,
                            )
                        }
                    }
                }
        }

    private fun count(
        sql: String,
        vararg args: Any,
    ): Int =
        PayloadDb.superuser().use { c ->
            c.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun jobs(
        tenant: TenantId,
        doc: UUID,
    ): Int =
        count(
            "select count(*) from payload.work_queue where tenant_id = ? and partition_key = ?",
            tenant.value,
            doc.toString(),
        )

    private val applied1 = PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)

    @Test
    fun `INV-AC-22936 stores the canonical document and enqueues when the decision exists`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        tx(t1) { decisions.store(d1, 1, "TYPE_2") } shouldBe true
        log.add(created(t1, "ev-1", d1, routing = """{"direction":"SALE","counterpartyOnNetwork":true}"""))
        consumer.pollOnce() shouldBe applied1
        row(t1, d1)?.document shouldBe
            """{"totals": {"value": {"totalIncludingTax": "80.00"}}, "currency": {"value": "SGD"}}"""
        jobs(t1, d1) shouldBe 1
    }

    @Test
    fun `INV-AC-22936 stores it without a job when no decision`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        log.add(created(t1, "ev-1", d2))
        consumer.pollOnce() shouldBe applied1
        (row(t1, d2) != null) shouldBe true
        jobs(t1, d2) shouldBe 0
    }

    @Test
    fun `INV-AC-22936 redelivery keeps the first document`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        tx(t1) { decisions.store(d1, 1, "TYPE_2") }
        log.add(created(t1, "ev-1", d1, document = """{"first":true}"""))
        log.add(created(t1, "ev-2", d1, document = """{"first":false}"""))
        consumer.pollOnce() shouldBe PollResult(applied = 1, ignored = 1, duplicates = 0, stale = 0, deadLettered = 0)
        row(t1, d1)?.document shouldBe """{"first": true}"""
        jobs(t1, d1) shouldBe 1
    }

    @Test
    fun `INV-AC-22936 string canonical document is dead lettered`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        log.add(created(t1, "ev-1", d1, document = "\"text\""))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)
        row(t1, d1) shouldBe null
        count(
            "select count(*) from payload.document_version_fact where tenant_id = ? and doc_uuid = ?",
            t1.value,
            d1,
        ) shouldBe 0
        tx(t1) { dsl.fetchCount(EVENT_DEAD_LETTER, EVENT_DEAD_LETTER.CONSUMER.eq(consumerName)) } shouldBe 1
    }

    @Test
    fun `INV-AC-22936 routing facts are stored with the document`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        log.add(created(t1, "ev-1", d1, routing = """{"direction":"SALE","counterpartyOnNetwork":true}"""))
        log.add(created(t1, "ev-2", d2, routing = """{"direction":"PURCHASE","counterpartyOnNetwork":false}"""))
        consumer.pollOnce()
        row(t1, d1)?.direction shouldBe "SALE"
        row(t1, d1)?.onNetwork shouldBe true
        row(t1, d2)?.direction shouldBe "PURCHASE"
        row(t1, d2)?.onNetwork shouldBe false
    }

    @Test
    fun `INV-AC-22936 absent routing facts are stored as null`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        log.add(created(t1, "ev-1", d2))
        log.add(created(t1, "ev-2", d1, routing = """{"direction":"SALE"}"""))
        consumer.pollOnce()
        row(t1, d2)?.direction shouldBe null
        row(t1, d2)?.onNetwork shouldBe null
        row(t1, d1)?.direction shouldBe "SALE"
        row(t1, d1)?.onNetwork shouldBe null
    }

    @Test
    fun `INV-AC-22936 unknown direction is dead lettered`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        log.add(created(t1, "ev-1", d1, routing = """{"direction":"sale"}"""))
        log.add(created(t1, "ev-2", d2, routing = """{"direction":"SALE","counterpartyOnNetwork":"yes"}"""))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 2)
        row(t1, d1) shouldBe null
        row(t1, d2) shouldBe null
    }

    @Test
    fun `INV-AC-22936 entity and total facts are unchanged`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        log.add(created(t1, "ev-1", d1, routing = """{"direction":"SALE"}"""))
        consumer.pollOnce()
        tx(t1) { facts.find(d1, 1) } shouldBe VersionFacts("E-1", BigDecimal("80.00"))
    }

    @Test
    fun `INV-AC-22936 input arriving while the decision transaction is open waits for it and enqueues once`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        val decisionStored = CountDownLatch(1)
        val secondCalled = CountDownLatch(1)
        val firstCommitted = AtomicLong(0)
        val lockReturned = AtomicLong(0)
        val failure = arrayOfNulls<Throwable>(2)

        fun thread(
            index: Int,
            body: () -> Unit,
        ) = Thread { runCatching(body).onFailure { failure[index] = it } }
        val first =
            thread(0) {
                tx(t1) {
                    decisions.store(d1, 1, "TYPE_2")
                    trigger.onDecisionStored(d1, 1)
                    decisionStored.countDown()
                    secondCalled.await(10, TimeUnit.SECONDS)
                    Thread.sleep(800) // the second transaction is now blocked in the version lock
                    firstCommitted.set(System.nanoTime())
                }
            }
        val second =
            thread(1) {
                decisionStored.await(10, TimeUnit.SECONDS)
                tx(t1) {
                    secondCalled.countDown()
                    inputs.store(d1, 1, "{}", "SALE", true, null)
                    lockReturned.set(System.nanoTime())
                    trigger.onInputStored(d1, 1)
                }
            }
        listOf(first, second).forEach { it.start() }
        listOf(first, second).forEach { it.join(30_000) }
        failure.toList() shouldBe listOf(null, null)
        (lockReturned.get() >= firstCommitted.get()) shouldBe true
        jobs(t1, d1) shouldBe 1
    }

    @Test
    fun `INV-AC-22936 other tenant reads nothing`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        log.add(created(t1, "ev-1", d1))
        consumer.pollOnce()
        row(TenantId(UUID.randomUUID()), d1) shouldBe null
        (row(t1, d1) != null) shouldBe true
    }
}
