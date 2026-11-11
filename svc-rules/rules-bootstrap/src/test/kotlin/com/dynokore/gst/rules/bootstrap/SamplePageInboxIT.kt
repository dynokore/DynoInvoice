package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.events.DryRunSampleEvents
import com.dynokore.gst.rules.adapters.events.DryRunSampleRecordedHandler
import com.dynokore.gst.rules.adapters.events.DryRunSampleRequest
import com.dynokore.gst.rules.adapters.events.EventHistoricalSamplePort
import com.dynokore.gst.rules.adapters.persistence.JooqSamplePageStore
import com.dynokore.gst.rules.application.DryRunWindow
import com.dynokore.gst.rules.application.SamplePage
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val COUNT_PAGES = "select count(*) from rules.dry_run_sample_page where dry_run_id = ?"
private const val COUNT_ITEMS = "select count(*) from rules.dry_run_sample_item where dry_run_id = ?"
private const val OUTBOX_ROWS = "select count(*) from rules.outbox where type = ? and subject = ?"
private const val OUTBOX_DATA = "select data::text from rules.outbox where type = ? and subject = ?"
private const val OUTBOX_TENANT = "select tenant_id::text from rules.outbox where type = ? and subject = ?"
private const val OUTBOX_ENTITY = "select entity_ref from rules.outbox where type = ? and subject = ?"
private const val OUTBOX_TOPIC = "select topic from rules.outbox where type = ? and subject = ?"
private const val ENTITY = "ENT-3F2A9C"
private const val DOC_A = "0b1f2c3d-0000-4000-8000-000000000001"
private const val DOC_B = "0b1f2c3d-0000-4000-8000-000000000002"
private const val DOC_C = "0b1f2c3d-0000-4000-8000-000000000003"
private const val DOC_D = "0b1f2c3d-0000-4000-8000-000000000004"
private const val PERIOD_33 = "2026-01-0123456789012345678901234"

/**
 * The sample pages of a dry run on real PostgreSQL (INV-T-19903, INV-AC-19905; INV-REQ-772, INV-REQ-1303, INV-Q-4995):
 * pages published from a canonical-svc stub through an in-memory event log are consumed by the real inbox consumer
 * (`JdbcInboxConsumer`) with the real handler and store, and [EventHistoricalSamplePort] returns them as published.
 * The request is published by [DryRunSampleEvents] in a command transaction. Only the event-log read is a list.
 */
@SpringBootTest(classes = [RulesApplication::class])
class SamplePageInboxIT(
    @Autowired private val tx: TransactionRunner,
    @Autowired private val tenants: TenantContext,
    @Autowired private val db: DSLContext,
    @Autowired private val scope: TenantScope,
    @Autowired private val sink: RebuildRequiredSink,
    @Autowired private val events: DryRunSampleEvents,
) {
    private val runtime: TenancyProbe = TenancyProbe.connect(pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")
    private val json = EnvelopeJson()
    private val mapper = JsonMapper.builder().build()
    private val tenant = TenantId(UUID.randomUUID())
    private val other = TenantId(UUID.randomUUID())
    private val store = JooqSamplePageStore(db, tenants)
    private val window = DryRunWindow("2026-01", "2026-03", 3)

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

    /** The canonical-svc stub: publishes pages to the log; a consumer of its own name reads them (offset row per name). */
    private inner class Harness {
        val log = Log()
        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription("rules-svc-" + UUID.randomUUID(), DryRunSampleEvents.RECORDED_TOPIC, "rules"),
                EventCatalogue(listOf(DryRunSampleEvents.recordedEntry())),
                log,
                scope,
                db,
                DryRunSampleRecordedHandler(store, Clock.systemUTC()),
                sink,
                Clock.systemUTC(),
            )

        fun publish(
            who: TenantId,
            dryRunId: String,
            page: SamplePage,
            eventId: String = UUID.randomUUID().toString(),
        ) {
            val data =
                mapper.writeValueAsString(
                    linkedMapOf(
                        "dryRunId" to dryRunId,
                        "pageNumber" to page.number,
                        "lastPage" to page.lastPage,
                        "documents" to page.documents,
                    ),
                )
            log.add(
                json.write(
                    Envelope(
                        id = EventId(eventId),
                        source = "/gst/canonical-svc",
                        type = DryRunSampleEvents.RECORDED,
                        subject = dryRunId,
                        time = Instant.parse("2026-11-11T02:00:00Z"),
                        tenant = who,
                        entity = EntityRef(ENTITY),
                        docUuid = null,
                        transactionDate = null,
                        docSequence = null,
                        instanceId = "canonical-svc-0",
                        correlation = CorrelationId("corr-1"),
                        causation = CausationId("cause-1"),
                        dataJson = data,
                    ),
                ),
            )
        }

        fun poll() = consumer.pollOnce()
    }

    private fun <T> asTenant(
        who: TenantId = tenant,
        block: () -> T,
    ): T = tenants.bind(who, "rules-actor") { tx.inTransaction { block() } }

    private fun port() = EventHistoricalSamplePort(store, Clock.systemUTC())

    private fun item(
        doc: String?,
        period: String?,
        date: String = "2026-01-15",
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "entityRef" to ENTITY,
            "transactionDate" to date,
            "internalTaxCode" to "SR",
            "documentFunction" to "BILL_FOR_PAYMENT",
            "taxableValue" to "100.00",
            "gstValue" to "9.00",
        ).also { m ->
            doc?.let { m["docUuid"] = it }
            period?.let { m["periodRef"] = it }
            m.putAll(extra)
        }

    /** Three pages, the last flagged: a missing periodRef, a docUuid that is no UUID, an over-long and an empty one. */
    private fun pages(): List<SamplePage> =
        listOf(
            SamplePage(1, false, listOf(item(DOC_A, "2026-01"), item(DOC_B, null, "2026-01-20"))),
            SamplePage(
                2,
                false,
                listOf(
                    item("not-a-uuid", "2026-02", "2026-02-03", mapOf("supplyType" to "ZERO_RATED")),
                    item(DOC_C, PERIOD_33, "2026-02-04"),
                    item(DOC_D, "", "2026-02-05"),
                ),
            ),
            SamplePage(
                3,
                true,
                listOf(
                    item(
                        null,
                        "2026-03",
                        "2026-03-09",
                        mapOf(
                            "documentFunction" to "ADJUSTMENT",
                            "precedingDocumentDate" to "2026-01-20",
                            "taxableValue" to "0.00",
                            "gstValue" to "0.00",
                            "sgdValuesAbsent" to true,
                        ),
                    ),
                ),
            ),
        )

    private fun Harness.publishAll(
        who: TenantId,
        run: String,
        all: List<SamplePage> = pages(),
    ) = all.forEach { publish(who, run, it) }

    private fun count(
        sql: String,
        run: String,
        who: TenantId = tenant,
    ): String = runtime.inTenant(who) { fetchValue(sql, run) }.toString()

    private fun outbox(
        sql: String,
        run: String,
    ): String? = runtime.inTenant(tenant) { fetchValue(sql, DryRunSampleEvents.REQUESTED, run) }?.toString()

    @Test
    fun `INV-AC-19905 published pages are returned as published, page by page and member by member`() {
        val run = UUID.randomUUID().toString()
        val h = Harness()
        h.publishAll(tenant, run)
        h.poll().applied shouldBe 3
        val read = asTenant { port().pages(run, window, Duration.ZERO) }
        read shouldBe pages()
        read.map { it.number } shouldBe listOf(1, 2, 3)
        read.map { it.lastPage } shouldBe listOf(false, false, true)
        for ((published, returned) in pages().flatMap { it.documents }.zip(read.flatMap { it.documents })) {
            returned.keys shouldBe published.keys
            for (member in published.keys) returned[member] shouldBe published[member]
        }
        count(COUNT_PAGES, run) shouldBe "3"
        count(COUNT_ITEMS, run) shouldBe "6"
    }

    @Test
    fun `INV-AC-19905 raw members - nothing is derived, generated, corrected or defaulted`() {
        val run = UUID.randomUUID().toString()
        val h = Harness()
        h.publishAll(tenant, run)
        h.poll()
        val items = asTenant { port().pages(run, window, Duration.ZERO) }.flatMap { it.documents }
        // missing periodRef: no key, not a month of transactionDate
        items[1].containsKey("periodRef") shouldBe false
        items[1]["docUuid"] shouldBe DOC_B
        // a docUuid that is not a UUID, kept as received
        items[2]["docUuid"] shouldBe "not-a-uuid"
        // a periodRef of 33 characters and an empty one, kept as received
        items[3]["periodRef"] shouldBe PERIOD_33
        (items[3]["periodRef"] as String).length shouldBe 33
        items[4]["periodRef"] shouldBe ""
        // a missing docUuid: no key, no generated UUID
        items[5].containsKey("docUuid") shouldBe false
        items[5]["sgdValuesAbsent"] shouldBe true
        items[5]["taxableValue"] shouldBe "0.00"
        items[0].containsKey("sgdValuesAbsent") shouldBe false
    }

    @Test
    fun `INV-AC-19905 a page redelivered with the same dry-run id and page number is stored once`() {
        val run = UUID.randomUUID().toString()
        val h = Harness()
        h.publishAll(tenant, run)
        // page 1 again under a new event id: the page, not the event, is the key
        h.publish(tenant, run, pages()[0], eventId = UUID.randomUUID().toString())
        val first = h.poll()
        first.applied shouldBe 3
        first.ignored shouldBe 1
        h.publish(tenant, run, pages()[1].copy(documents = listOf(item(DOC_A, "2026-12"))))
        h.poll().ignored shouldBe 1
        count(COUNT_PAGES, run) shouldBe "3"
        count(COUNT_ITEMS, run) shouldBe "6"
        asTenant { port().pages(run, window, Duration.ZERO) } shouldBe pages()
    }

    @Test
    fun `INV-AC-19905 a page of another tenant's run is not readable`() {
        val run = UUID.randomUUID().toString()
        val otherRun = UUID.randomUUID().toString()
        val h = Harness()
        h.publishAll(tenant, run)
        h.publishAll(other, otherRun, pages().take(1))
        h.poll().applied shouldBe 4
        count(COUNT_PAGES, run, other) shouldBe "0"
        count(COUNT_ITEMS, run, other) shouldBe "0"
        asTenant(other) { port().pages(run, window, Duration.ZERO) } shouldBe emptyList()
        asTenant(other) { port().pages(otherRun, window, Duration.ZERO) }.size shouldBe 1
        count(COUNT_PAGES, otherRun, tenant) shouldBe "0"
    }

    @Test
    fun `INV-AC-19905 release deletes the page and item rows of that run only`() {
        val run = UUID.randomUUID().toString()
        val otherRun = UUID.randomUUID().toString()
        val h = Harness()
        h.publishAll(tenant, run)
        h.publishAll(other, otherRun)
        h.poll()
        count(COUNT_PAGES, run) shouldBe "3"
        asTenant { port().release(run) }
        count(COUNT_PAGES, run) shouldBe "0"
        count(COUNT_ITEMS, run) shouldBe "0"
        count(COUNT_PAGES, otherRun, other) shouldBe "3"
        // a second release is not an error
        asTenant { port().release(run) }
        // another tenant's release of this id deletes nothing of the other's run
        asTenant(tenant) { port().release(otherRun) }
        count(COUNT_PAGES, otherRun, other) shouldBe "3"
    }

    @Test
    fun `INV-AC-19905 the sample is complete only with the last page and every page below it`() {
        val run = UUID.randomUUID().toString()
        val h = Harness()
        h.publish(tenant, run, pages()[0])
        h.publish(tenant, run, pages()[2])
        h.poll()
        val started = Instant.now()
        val read = asTenant { port().pages(run, window, Duration.ofMillis(300)) }
        read.map { it.number } shouldBe listOf(1, 3)
        (Duration.between(started, Instant.now()) >= Duration.ofMillis(300)) shouldBe true
        // page 2 arrives: the wait ends at once with all three
        h.publish(tenant, run, pages()[1])
        h.poll()
        asTenant { port().pages(run, window, Duration.ofSeconds(30)) }.map { it.number } shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `INV-AC-19905 the request carries run, tenant, entity, window and pack versions in the command transaction`() {
        val run = UUID.randomUUID().toString()
        val request =
            DryRunSampleRequest(run, tenant, ENTITY, window, "current-1", "candidate-2", "corr-9", "cause-9")
        asTenant { events.publish(request) }
        outbox(OUTBOX_ROWS, run) shouldBe "1"
        outbox(OUTBOX_TOPIC, run) shouldBe DryRunSampleEvents.REQUESTED_TOPIC
        outbox(OUTBOX_TENANT, run) shouldBe tenant.value.toString()
        outbox(OUTBOX_ENTITY, run) shouldBe ENTITY
        val data = mapper.readTree(outbox(OUTBOX_DATA, run))
        data.size() shouldBe 7
        data.get("dryRunId").asString() shouldBe run
        data.get("entityRef").asString() shouldBe ENTITY
        data.get("firstPeriodRef").asString() shouldBe "2026-01"
        data.get("lastPeriodRef").asString() shouldBe "2026-03"
        data.get("periodCount").asInt() shouldBe 3
        data.get("currentPackVersion").asString() shouldBe "current-1"
        data.get("candidatePackVersion").asString() shouldBe "candidate-2"
        // probe: a command that rolls back leaves no request row
        val rolledBack = UUID.randomUUID().toString()
        shouldThrow<IllegalStateException> {
            asTenant {
                events.publish(request.copy(dryRunId = rolledBack))
                error("the command fails after it published the request")
            }
        }
        outbox(OUTBOX_ROWS, rolledBack) shouldBe "0"
    }

    @Test
    fun `INV-AC-19905 probe - a page that breaks the shape of the page is dead-lettered and stores nothing`() {
        val run = UUID.randomUUID().toString()
        val h = Harness()
        h.publish(tenant, run, SamplePage(1, true, listOf(item(DOC_A, "2026-01") - "taxableValue")))
        h.poll().deadLettered shouldBe 1
        count(COUNT_PAGES, run) shouldBe "0"
        count(COUNT_ITEMS, run) shouldBe "0"
    }

    companion object {
        private val pg get() = PostgresRules.pg

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = PostgresRules.register(registry)
    }
}
