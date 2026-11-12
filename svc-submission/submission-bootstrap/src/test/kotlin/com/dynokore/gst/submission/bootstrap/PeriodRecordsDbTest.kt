package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.period.PeriodAnswer
import com.dynokore.gst.submission.application.period.PeriodOfDate
import com.dynokore.gst.submission.application.period.PeriodRecord
import com.dynokore.gst.submission.application.period.PeriodRecordStore
import com.dynokore.gst.submission.application.period.PeriodRecordsChangedDataReader
import com.dynokore.gst.submission.application.period.PeriodRecordsChangedHandler
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
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.PeriodRecord as PeriodRecordTable

/**
 * The `PeriodRecordsChanged` consumer against a real database (P109, INV-AC-20678): the library `JdbcInboxConsumer`
 * drives the real `PeriodRecordsChangedHandler` bean over the period record table, so the replace and the inbox row
 * commit together and a failure rolls both back.
 */
class PeriodRecordsDbTest {
    private val actor = "svc:submission-svc"
    private val json = EnvelopeJson()

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
    ) : EventHandler {
        val outcomes = mutableListOf<HandleOutcome>()

        override fun handle(event: Envelope): HandleOutcome = inner.handle(event).also { outcomes += it }
    }

    private inner class Kit(
        val context: ApplicationContext,
    ) {
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val handler: EventHandler = context.getBean("periodRecordsChangedHandler", EventHandler::class.java)
        val periodOfDate: PeriodOfDate = context.getBean(PeriodOfDate::class.java)
        val dataReader: PeriodRecordsChangedDataReader = context.getBean(PeriodRecordsChangedDataReader::class.java)
        val store: PeriodRecordStore = context.getBean("periodRecordStore", PeriodRecordStore::class.java)
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val catalogue: EventCatalogue = context.getBean(EventCatalogue::class.java)
        val reader = ListReader()
        val tenant: UUID = UUID.randomUUID()
        val other: UUID = UUID.randomUUID()
        private var offset = 0L
        private val consumerName = "t20679-" + UUID.randomUUID()

        fun consumer(handler: EventHandler): JdbcInboxConsumer =
            JdbcInboxConsumer(
                ConsumerSubscription(consumerName, SubmissionEvents.PERIOD_RECORDS_CHANGED_TOPIC, "submission"),
                catalogue,
                reader,
                scope,
                TenancyJooq.dslContext(OutboxSchema.jooqTables("submission"), tenants, runner),
                handler,
                RebuildRequiredSink { },
            )

        fun deliver(envelope: Envelope) {
            reader.records += LogRecord(++offset, json.write(envelope))
        }

        fun <T> asTenant(
            id: UUID = tenant,
            block: () -> T,
        ): T = tenants.bind(TenantId(id), actor) { runner.inTransaction(block) }

        fun rows(id: UUID = tenant): List<String> =
            asTenant(id) {
                val t = PeriodRecordTable.PERIOD_RECORD
                dsl
                    .selectFrom(t)
                    .where(t.ENTITY_REF.eq("E-001"))
                    .orderBy(t.PERIOD_REF)
                    .fetch()
                    .map { "${it.periodRef}:${it.periodStart}:${it.periodEnd}:${it.version}" }
            }

        fun answer(date: String): PeriodAnswer =
            asTenant { periodOfDate.periodOf(TenantId(tenant), "E-001", LocalDate.parse(date)) }

        fun envelope(
            version: Int,
            periods: String,
            id: String = UUID.randomUUID().toString(),
            version0: String = version.toString(),
        ): Envelope =
            Envelope(
                id = EventId(id),
                source = "/gst/tenancy-svc",
                type = SubmissionEvents.PERIOD_RECORDS_CHANGED_TYPE,
                subject = "E-001",
                time = Instant.parse("2026-11-12T08:00:00Z"),
                tenant = TenantId(tenant),
                entity = EntityRef("E-001"),
                docUuid = DocUuid(UUID.randomUUID()),
                transactionDate = LocalDate.parse("2026-03-20"),
                docSequence = null,
                instanceId = "tenancy-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson = """{"entityRef":"E-001","version":$version0,"periods":$periods}""",
            )
    }

    private fun p(
        ref: String,
        start: String,
        end: String,
    ) = """{"periodRef":"$ref","periodStart":"$start","periodEnd":"$end"}"""

    private val q1 = p("Q1", "2026-01-01", "2026-03-31")
    private val q2 = p("Q2", "2026-04-01", "2026-06-30")
    private val q2Short = p("Q2", "2026-04-01", "2026-06-29")
    private val q3 = p("Q3", "2026-07-01", "2026-09-30")

    private fun run(
        dir: Path,
        block: Kit.() -> Unit,
    ) {
        SubmissionContext.online(dir).run { context -> Kit(context).block() }
    }

    @Test
    fun `INV-AC-20678 the real handler applies E1 then E2 as a set and ignores repeats and an older version`(
        @TempDir dir: Path,
    ) = run(dir) {
        val e1 = envelope(1, "[$q1,$q2]")
        val e2 = envelope(2, "[$q2Short,$q3]")
        val recording = Recording(handler)
        deliver(e1)
        consumer(recording).also { it.pollOnce() shouldBe PollResult(1, 0, 0, 0, 0) }
        rows() shouldBe listOf("Q1:2026-01-01:2026-03-31:1", "Q2:2026-04-01:2026-06-30:1")
        answer("2026-03-31") shouldBe PeriodAnswer.Known("Q1")

        val second = consumer(recording)
        deliver(e2)
        second.pollOnce().applied shouldBe 1
        rows() shouldBe listOf("Q2:2026-04-01:2026-06-29:2", "Q3:2026-07-01:2026-09-30:2")

        deliver(envelope(1, "[$q1,$q2]"))
        deliver(envelope(2, "[$q2Short,$q3]"))
        deliver(envelope(1, "[$q1]"))
        second.pollOnce().ignored shouldBe 3
        val applied = HandleOutcome.APPLIED
        val ignored = HandleOutcome.IGNORED
        recording.outcomes shouldBe
            listOf(applied, applied, ignored, ignored, ignored)
        rows() shouldBe listOf("Q2:2026-04-01:2026-06-29:2", "Q3:2026-07-01:2026-09-30:2")
    }

    @Test
    fun `INV-AC-20678 PeriodOfDate answers the containing period or Unknown`(
        @TempDir dir: Path,
    ) = run(dir) {
        deliver(envelope(1, "[$q1,$q2]"))
        deliver(envelope(2, "[$q2Short,$q3]"))
        consumer(handler).pollOnce().applied shouldBe 2
        answer("2026-03-31") shouldBe PeriodAnswer.Unknown
        answer("2026-06-30") shouldBe PeriodAnswer.Unknown
        answer("2026-04-01") shouldBe PeriodAnswer.Known("Q2")
        answer("2026-06-29") shouldBe PeriodAnswer.Known("Q2")
        answer("2026-12-31") shouldBe PeriodAnswer.Unknown
    }

    @Test
    fun `INV-AC-20678 two rows containing one date give Unknown`(
        @TempDir dir: Path,
    ) = run(dir) {
        asTenant {
            val t = PeriodRecordTable.PERIOD_RECORD
            val spans = listOf(Triple("A", "2026-01-01", "2026-06-30"), Triple("B", "2026-03-01", "2026-09-30"))
            spans.forEach { (ref, from, to) ->
                dsl
                    .insertInto(t)
                    .set(t.TENANT_ID, tenant)
                    .set(t.ENTITY_REF, "E-001")
                    .set(t.PERIOD_REF, ref)
                    .set(t.PERIOD_START, LocalDate.parse(from))
                    .set(t.PERIOD_END, LocalDate.parse(to))
                    .set(t.VERSION, 1)
                    .execute()
            }
        }
        answer("2026-04-01") shouldBe PeriodAnswer.Unknown
        answer("2026-02-01") shouldBe PeriodAnswer.Known("A")
    }

    @Test
    fun `INV-AC-20678 each malformed event is dead-lettered and leaves the rows as before`(
        @TempDir dir: Path,
    ) = run(dir) {
        deliver(envelope(1, "[$q1,$q2]"))
        consumer(handler).pollOnce().applied shouldBe 1
        val before = rows()
        val c = consumer(handler)
        deliver(envelope(2, "[${p("Q1", "2026-13-01", "2026-03-31")}]"))
        deliver(envelope(3, "[$q1]").let { it.copy(dataJson = """{"entityRef":"E-001","version":3}""") })
        deliver(envelope(4, "[${"""{"periodRef":"Q1","periodStart":"2026-01-01"}"""}]"))
        val result = c.pollOnce()
        result.deadLettered shouldBe 3
        result.applied shouldBe 0
        rows() shouldBe before
    }

    @Test
    fun `INV-AC-20678 a replace that fails after the delete rolls back to the previous set`(
        @TempDir dir: Path,
    ) = run(dir) {
        deliver(envelope(2, "[$q2Short,$q3]"))
        consumer(handler).pollOnce().applied shouldBe 1
        val failingStore =
            object : PeriodRecordStore by store {
                override fun replace(
                    tenant: TenantId,
                    entityRef: String,
                    version: Int,
                    periods: List<PeriodRecord>,
                ) {
                    store.replace(tenant, entityRef, version, periods)
                    error("forced after the replace")
                }
            }
        val failing = PeriodRecordsChangedHandler(tenants, dataReader, failingStore)
        deliver(envelope(3, "[$q3]"))
        val c = consumer(failing)
        shouldThrow<IllegalStateException> { c.pollOnce() }
        rows() shouldBe listOf("Q2:2026-04-01:2026-06-29:2", "Q3:2026-07-01:2026-09-30:2")
    }

    @Test
    fun `INV-AC-20678 another tenant sees only its own rows`(
        @TempDir dir: Path,
    ) = run(dir) {
        val otherQ1 = PeriodRecord("Q1", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"))
        asTenant(other) { store.replace(TenantId(other), "E-001", 1, listOf(otherQ1)) }
        deliver(envelope(1, "[$q2]"))
        consumer(handler).pollOnce().applied shouldBe 1
        rows() shouldBe listOf("Q2:2026-04-01:2026-06-30:1")
        rows(other) shouldBe listOf("Q1:2026-01-01:2026-03-31:1")
        answer("2026-02-01") shouldBe PeriodAnswer.Unknown
    }

    @Test
    fun `INV-AC-20678 the catalogue lists the type on the topic and only that topic is subscribed`(
        @TempDir dir: Path,
    ) = run(dir) {
        val entry = catalogue.find("com.dynokore.gst.tenancy.PeriodRecordsChanged.v1")!!
        entry.topic shouldBe "gst.tenancy.period-records-changed.v1"
        entry.producers shouldBe setOf("tenancy-svc")
        PeriodRecordsConsumer.subscription().topic shouldBe "gst.tenancy.period-records-changed.v1"
        (PeriodRecordsConsumer.subscription().topic == "gst.tenancy.period-record-changed.v1") shouldBe false
        catalogue.find("com.dynokore.gst.tenancy.PeriodRecordChanged.v1") shouldBe null
    }
}
