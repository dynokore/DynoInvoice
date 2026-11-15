package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.PayloadDb
import com.dynokore.gst.payload.adapters.events.CanonicalVersionCreatedHandler
import com.dynokore.gst.payload.adapters.events.SubmissionTypeDecidedHandler
import com.dynokore.gst.payload.adapters.persistence.JooqBuildInputs
import com.dynokore.gst.payload.adapters.persistence.JooqBuildJobs
import com.dynokore.gst.payload.adapters.persistence.JooqTypeDecisionStore
import com.dynokore.gst.payload.adapters.persistence.JooqVersionFactsStore
import com.dynokore.gst.payload.adapters.persistence.jooq.tables.EventDeadLetter.Companion.EVENT_DEAD_LETTER
import com.dynokore.gst.payload.application.build.BuildTrigger
import com.dynokore.gst.payload.application.version.RecordVersionFacts
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
import io.kotest.matchers.shouldNotBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * INV-AC-22934 through the real inbox consumer on the tables of schema `payload` over PostgreSQL (P109). Only the
 * event-log read is a list in memory. The canonical input rows are inserted by the test (their write side is INV-T-22937).
 */
class SubmissionTypeDecidedConsumerTest {
    private val actor = "svc:payload-svc"
    private val d1 = UUID.fromString("0b1f2c3d-0000-4000-8000-000000000011")
    private val d2 = UUID.fromString("0b1f2c3d-0000-4000-8000-000000000012")
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
        val inputs: JooqBuildInputs = context.getBean(JooqBuildInputs::class.java)
        val facts: JooqVersionFactsStore = context.getBean(JooqVersionFactsStore::class.java)
        val jobs: JooqBuildJobs = context.getBean(JooqBuildJobs::class.java)
        val trigger: BuildTrigger = context.getBean(BuildTrigger::class.java)
        val catalogue: EventCatalogue = context.getBean(EventCatalogue::class.java)
        val log = Log()
        val consumerName = SubmissionTypeDecidedConsumerConfiguration.CONSUMER + "-" + UUID.randomUUID()

        fun consumer(
            name: String = consumerName,
            topic: String = SubmissionTypeDecidedConsumerConfiguration.TOPIC,
            reader: EventLogReader = log,
        ) = JdbcInboxConsumer(
            ConsumerSubscription(name, topic, SubmissionTypeDecidedConsumerConfiguration.SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            SubmissionTypeDecidedHandler(decisions, trigger),
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

    private fun decided(
        tenant: TenantId,
        eventId: String,
        doc: UUID,
        type: String = "TYPE_2",
        version: Int = 1,
        docsequence: Boolean = true,
        typeJson: String = "\"$type\"",
    ): String {
        val sequence = if (docsequence) """"docsequence":3,""" else ""
        val envelope =
            Envelope(
                id = EventId(eventId),
                source = "/gst/submission-svc",
                type = SubmissionTypeDecidedConsumerConfiguration.TYPE,
                subject = doc.toString(),
                time = Instant.parse("2026-10-20T10:00:00Z"),
                tenant = tenant,
                entity = EntityRef("E-1"),
                docUuid = DocUuid(doc),
                transactionDate = LocalDate.of(2026, 1, 15),
                docSequence = eventId.removePrefix("ev-").toLong(),
                instanceId = "submission-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson = """{"docUuid":"$doc","version":$version,$sequence"submissionType":$typeJson}""",
            )
        return json.write(envelope)
    }

    private fun storeInput(
        tenant: TenantId,
        doc: UUID,
        version: Int = 1,
    ) {
        PayloadDb.superuser().use { c ->
            c.prepareStatement(
                "insert into payload.canonical_input(tenant_id, doc_uuid, version, canonical_document) values (?, ?, ?, '{}'::jsonb)",
            ).use { st ->
                st.setObject(1, tenant.value)
                st.setObject(2, doc)
                st.setInt(3, version)
                st.executeUpdate()
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
    ): Int = count("select count(*) from payload.work_queue where tenant_id = ? and partition_key = ?", tenant.value, doc.toString())

    private fun storedType(
        tenant: TenantId,
        doc: UUID,
    ): String? =
        PayloadDb.superuser().use { c ->
            c.prepareStatement("select submission_type from payload.submission_type_decision where tenant_id = ? and doc_uuid = ?").use { st ->
                st.setObject(1, tenant.value)
                st.setObject(2, doc)
                st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    @Test
    fun `INV-AC-22934 stores the decision and enqueues when the input exists`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        storeInput(t1, d1)
        log.add(decided(t1, "ev-1", d1))
        consumer().pollOnce() shouldBe PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
        storedType(t1, d1) shouldBe "TYPE_2"
        jobs(t1, d1) shouldBe 1
    }

    @Test
    fun `INV-AC-22934 stores the decision without a job when no input`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        log.add(decided(t1, "ev-1", d2))
        consumer().pollOnce() shouldBe PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
        storedType(t1, d2) shouldBe "TYPE_2"
        jobs(t1, d2) shouldBe 0
    }

    @Test
    fun `INV-AC-22934 redelivery keeps the first type`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        storeInput(t1, d1)
        log.add(decided(t1, "ev-1", d1))
        log.add(decided(t1, "ev-2", d1, type = "TYPE_3")) // another event for the same version, another type
        consumer().pollOnce() shouldBe PollResult(applied = 1, ignored = 1, duplicates = 0, stale = 0, deadLettered = 0)
        storedType(t1, d1) shouldBe "TYPE_2"
        jobs(t1, d1) shouldBe 1
    }

    @Test
    fun `INV-AC-22934 unknown type is dead lettered`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        log.add(decided(t1, "ev-1", d1, type = "TYPE_9"))
        log.add(decided(t1, "ev-2", d2, typeJson = "2")) // a number is not text
        consumer().pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 2)
        storedType(t1, d1) shouldBe null
        storedType(t1, d2) shouldBe null
        tx(t1) { dsl.fetchCount(EVENT_DEAD_LETTER, EVENT_DEAD_LETTER.CONSUMER.eq(consumerName)) } shouldBe 2
    }

    @Test
    fun `INV-AC-22934 missing docsequence is dead lettered`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        log.add(decided(t1, "ev-1", d1, docsequence = false))
        consumer().pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)
        storedType(t1, d1) shouldBe null
        jobs(t1, d1) shouldBe 0
    }

    @Test
    fun `INV-AC-22934 other tenant reads nothing`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        val t2 = TenantId(UUID.randomUUID())
        storeInput(t1, d1)
        log.add(decided(t1, "ev-1", d1))
        consumer().pollOnce()
        tx(t1) { decisions.exists(d1, 1) && inputs.exists(d1, 1) } shouldBe true
        tx(t2) { decisions.exists(d1, 1) } shouldBe false
        tx(t2) { inputs.exists(d1, 1) } shouldBe false
    }

    @Test
    fun `INV-AC-22934 redelivery after the job enqueues nothing more`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        storeInput(t1, d1)
        log.add(decided(t1, "ev-1", d1))
        consumer().pollOnce()
        jobs(t1, d1) shouldBe 1
        tx(t1) { trigger.onDecisionStored(d1, 1) } shouldBe false
        tx(t1) { trigger.onInputStored(d1, 1) } shouldBe false
        log.add(decided(t1, "ev-2", d1))
        consumer().pollOnce()
        jobs(t1, d1) shouldBe 1
    }

    @Test
    fun `INV-AC-22934 decision and input committed at the same moment enqueue exactly one job`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        val decisionStored = CountDownLatch(1)
        val secondCalled = CountDownLatch(1)
        val firstCommitted = AtomicLong(0)
        val lockReturned = AtomicLong(0)
        val failure = arrayOfNulls<Throwable>(2)
        val first =
            Thread {
                runCatching {
                    tx(t1) {
                        decisions.store(d1, 1, "TYPE_2")
                        trigger.onDecisionStored(d1, 1)
                        decisionStored.countDown()
                        secondCalled.await(10, TimeUnit.SECONDS)
                        Thread.sleep(800) // the second transaction is now blocked in lockVersion
                        firstCommitted.set(System.nanoTime())
                    }
                }.onFailure { failure[0] = it }
            }
        val second =
            Thread {
                runCatching {
                    decisionStored.await(10, TimeUnit.SECONDS)
                    tx(t1) {
                        secondCalled.countDown()
                        jobs.lockVersion(d1, 1)
                        lockReturned.set(System.nanoTime())
                        storeInput(t1, d1)
                        trigger.onInputStored(d1, 1)
                    }
                }.onFailure { failure[1] = it }
            }
        first.start()
        second.start()
        first.join(30_000)
        second.join(30_000)
        failure[0] shouldBe null
        failure[1] shouldBe null
        firstCommitted.get() shouldNotBe 0L
        (lockReturned.get() >= firstCommitted.get()) shouldBe true
        jobs(t1, d1) shouldBe 1
    }

    @Test
    fun `INV-AC-22934 the decision topic has its own offset row`(
        @TempDir dir: Path,
    ) = run(dir) { t1 ->
        val name = SubmissionTypeDecidedConsumerConfiguration.CONSUMER
        val versions = Log()
        log.add(decided(t1, "ev-1", d1))
        val created =
            decided(t1, "ev-1", d1)
                .replace(SubmissionTypeDecidedConsumerConfiguration.TYPE, "com.dynokore.gst.canonical.CanonicalVersionCreated.v1")
                .replace(""""submissionType":"TYPE_2"""", """"canonicalDocument":{"totals":{"value":{"totalIncludingTax":"1.00"}}}""")
        versions.add(created)
        consumer(name).pollOnce()
        val factsConsumer =
            JdbcInboxConsumer(
                ConsumerSubscription(name, VersionFactsConsumerConfiguration.TOPIC, VersionFactsConsumerConfiguration.SCHEMA),
                catalogue,
                versions,
                scope,
                dsl,
                CanonicalVersionCreatedHandler(RecordVersionFacts(facts)),
                RebuildRequiredSink { },
                Clock.systemUTC(),
            )
        factsConsumer.pollOnce()
        count("select count(*) from payload.consumer_offset where consumer = ?", name) shouldBe 2
    }
}
