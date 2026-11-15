package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.events.PeriodRecordsChangedEvents
import com.dynokore.gst.rules.adapters.events.PeriodRecordsChangedHandler
import com.dynokore.gst.rules.adapters.events.StoredLastCompletePeriodPort
import com.dynokore.gst.rules.adapters.persistence.JooqPeriodRecordsStore
import com.dynokore.gst.rules.application.LastCompletePeriodPort
import com.dynokore.gst.rules.served.TENANT_A
import com.dynokore.platform.configresolver.BusinessDate
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
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val ENTITY = "E-1"
private const val SRT_013_CODE = "SRT-013"
private const val COUNT_SETS = "select count(*) from rules.entity_period_set where entity_ref = ?"
private const val COUNT_SPANS = "select count(*) from rules.entity_period where entity_ref = ?"
private const val COUNT_OUTBOX = "select count(*) from rules.outbox"
private const val CLEAN_SETS =
    "with d as (delete from rules.entity_period_set where entity_ref = ? returning 1) select count(*) from d"

/**
 * The period records of an entity on real PostgreSQL (INV-T-20226, INV-AC-20223; INV-REQ-1303, INV-REQ-772):
 * `PeriodRecordsChanged` events published from a tenancy-svc stub through an in-memory event log are consumed by the
 * real inbox consumer with the real handler and store, and the stored port answers from them, also through the HTTP
 * boundary of a body-less `dryRun`.
 */
@SpringBootTest(
    classes = [DryRunFixturesWithoutCalendar::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "rules.dry-run.sample-wait=PT2S",
        "service-runtime.idempotency.schema=rules",
        "service-runtime.workload-identity.callers[0].service=console-bff",
        "service-runtime.workload-identity.callers[0].subject=subject-of-console-bff",
        "service-runtime.workload-identity.callers[1].service=console-bff-alt",
        "service-runtime.workload-identity.callers[1].subject=subject-of-console-bff-alt",
    ],
)
@Suppress("LongParameterList") // the collaborators are injected platform beans
class PeriodRecordsInboxIT(
    @LocalServerPort port: Int,
    @Autowired private val tx: TransactionRunner,
    @Autowired private val tenants: TenantContext,
    @Autowired private val db: DSLContext,
    @Autowired private val scope: TenantScope,
    @Autowired private val sink: RebuildRequiredSink,
    @Autowired private val context: ApplicationContext,
) : DryRunSupport(port) {
    private val runtime: TenancyProbe =
        TenancyProbe.connect(
            PostgresRules.pg.jdbcUrl,
            "rules_runtime",
            "rules_runtime_pw",
        )
    private val json = EnvelopeJson()
    private val store = JooqPeriodRecordsStore(db)
    private val other = TenantId(UUID.randomUUID())
    private val today: LocalDate = BusinessDate.today(Clock.systemUTC())

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

    /** The tenancy-svc stub publishes to the log; a consumer of its own name (one offset row) reads it. */
    private inner class Harness {
        val log = Log()
        val consumer =
            JdbcInboxConsumer(
                ConsumerSubscription("rules-svc-" + UUID.randomUUID(), PeriodRecordsChangedEvents.TOPIC, "rules"),
                EventCatalogue(listOf(PeriodRecordsChangedEvents.entry())),
                log,
                scope,
                db,
                PeriodRecordsChangedHandler(store, Clock.systemUTC()),
                sink,
                Clock.systemUTC(),
            )

        fun publish(
            who: TenantId,
            version: Int,
            periods: List<Triple<String, LocalDate, LocalDate>>,
        ) {
            val items =
                periods.joinToString(",") { (ref, start, end) ->
                    """{"periodRef":"$ref","periodStart":"$start","periodEnd":"$end"}"""
                }
            log.add(
                json.write(
                    Envelope(
                        id = EventId(UUID.randomUUID().toString()),
                        source = "/gst/tenancy-svc",
                        type = PeriodRecordsChangedEvents.TYPE,
                        subject = ENTITY,
                        time = Instant.parse("2026-11-16T02:00:00Z"),
                        tenant = who,
                        entity = EntityRef(ENTITY),
                        docUuid = null,
                        transactionDate = null,
                        docSequence = null,
                        instanceId = "tenancy-svc-0",
                        correlation = CorrelationId("corr-1"),
                        causation = CausationId("cause-1"),
                        dataJson = """{"entityRef":"$ENTITY","version":$version,"periods":[$items]}""",
                    ),
                ),
            )
        }

        fun poll() = consumer.pollOnce()
    }

    @BeforeEach
    @AfterEach
    fun clean() {
        // the database is shared by the test classes of the JVM: no period record outlives a case
        for (who in listOf(TENANT_A, other)) runtime.inTenant(who) { fetchValue(CLEAN_SETS, ENTITY) }
    }

    private fun <T> asTenant(
        who: TenantId = TENANT_A,
        block: () -> T,
    ): T = tenants.bind(who, "rules-actor") { tx.inTransaction { block() } }

    private fun count(
        sql: String,
        who: TenantId = TENANT_A,
        vararg binds: Any?,
    ): String = runtime.inTenant(who) { fetchValue(sql, *binds) }.toString()

    private fun span(
        ref: String,
        endsDaysAgo: Long,
    ) = Triple(ref, today.minusDays(endsDaysAgo + 29), today.minusDays(endsDaysAgo))

    private val q1 get() = span("2026-Q1", 100)
    private val q2 get() = span("2026-Q2", 10)
    private val q3 get() = span("2026-Q3", 0)

    @Test
    fun `INV-AC-20223 a delivered event is readable by its tenant only`() {
        val h = Harness()
        h.publish(TENANT_A, 1, listOf(q1, q2))
        h.poll().applied shouldBe 1
        count(COUNT_SETS, TENANT_A, ENTITY) shouldBe "1"
        count(COUNT_SPANS, TENANT_A, ENTITY) shouldBe "2"
        count(COUNT_SETS, other, ENTITY) shouldBe "0"
        count(COUNT_SPANS, other, ENTITY) shouldBe "0"
        asTenant { store.spans(TENANT_A, ENTITY) }!!.map { it.periodRef } shouldBe listOf("2026-Q1", "2026-Q2")
        asTenant(other) { store.spans(TENANT_A, ENTITY) } shouldBe null
        asTenant(other) { store.spans(other, ENTITY) } shouldBe null
    }

    @Test
    fun `INV-AC-20223 a newer version replaces and a superseded period is gone`() {
        val h = Harness()
        h.publish(TENANT_A, 2, listOf(q1, q2))
        h.poll().applied shouldBe 1
        h.publish(TENANT_A, 3, listOf(q2, q3))
        h.poll().applied shouldBe 1
        asTenant { store.spans(TENANT_A, ENTITY) }!!.map { it.periodRef } shouldBe listOf("2026-Q2", "2026-Q3")
        // an older and an equal version change nothing
        h.publish(TENANT_A, 2, listOf(q1))
        h.publish(TENANT_A, 3, listOf(q1))
        val ignored = h.poll()
        ignored.ignored shouldBe 2
        ignored.applied shouldBe 0
        asTenant { store.spans(TENANT_A, ENTITY) }!!.map { it.periodRef } shouldBe listOf("2026-Q2", "2026-Q3")
        count(COUNT_SETS, TENANT_A, ENTITY) shouldBe "1"
    }

    @Test
    fun `INV-AC-20223 handling the event writes no outbox row`() {
        val before = count(COUNT_OUTBOX)
        val h = Harness()
        h.publish(TENANT_A, 1, listOf(q1, q2))
        h.publish(TENANT_A, 1, listOf(q1))
        h.poll().let { it.applied shouldBe 1 }
        count(COUNT_OUTBOX) shouldBe before
    }

    @Test
    fun `INV-AC-20223 a body-less dryRun uses the stored period`() {
        val refusedLabel = label()
        val packId = candidate(refusedLabel)
        val before = snapshot()
        val refusal = dryRun(packId)
        refused(refusal, 503, SRT_013_CODE, before)
        refusal.body() shouldContain "period calendar"

        val h = Harness()
        // Q3 ends on the clock date, so it is not complete: the last complete period is Q2
        h.publish(TENANT_A, 1, listOf(q1, q2, q3))
        h.poll().applied shouldBe 1
        val label = label()
        val accepted = dryRun(candidate(label))
        withClue(accepted.body()) { (accepted.statusCode() == 200 || accepted.statusCode() == 202) shouldBe true }
        windowOf(label) shouldBe Triple("2026-Q2", "2026-Q2", 1)
    }

    @Test
    fun `INV-AC-20223 the context binds the stored port`() {
        val beans = context.getBeansOfType(LastCompletePeriodPort::class.java)
        beans.size shouldBe 1
        beans.values.single().javaClass shouldBe StoredLastCompletePeriodPort::class.java
        (beans.values.single().javaClass == FixtureLastCompletePeriodPort::class.java) shouldBe false
    }
}

/** A context that supplies the fixture calendar keeps the fixture; the stored port backs off. */
@SpringBootTest(classes = [DryRunFixtures::class, RulesApplication::class])
class StoredPortBackOffIT(
    @Autowired private val context: ApplicationContext,
) {
    @Test
    fun `INV-AC-20223 the context binds the stored port - a fixture bean keeps the fixture`() {
        val beans = context.getBeansOfType(LastCompletePeriodPort::class.java)
        beans.size shouldBe 1
        beans.values.single().javaClass shouldBe FixtureLastCompletePeriodPort::class.java
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = PostgresRules.register(registry)
    }
}
