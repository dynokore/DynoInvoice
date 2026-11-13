package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.SubmissionDb
import com.dynokore.gst.submission.application.pack.MarginUnit
import com.dynokore.gst.submission.application.pack.PackDefaultMargin
import com.dynokore.gst.submission.application.pack.PackMarginAnswer
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The pack margin consumers against a real database (P109, INV-AC-20786): the real handler beans and store of the
 * bootstrap context, driven by the library `JdbcInboxConsumer` over a fixed list of records. The producer of both
 * events is not implemented in rules-svc, so these tests feed the payloads themselves.
 */
class PackMarginConsumersDbTest {
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

    private inner class Kit(
        context: ApplicationContext,
    ) {
        val tenants: TenantContext = context.getBean(TenantContext::class.java)
        val runner: TransactionRunner = context.getBean(TransactionRunner::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val catalogue: EventCatalogue = context.getBean(EventCatalogue::class.java)
        val margins: PackDefaultMargin = context.getBean(PackDefaultMargin::class.java)
        val stagedHandler: EventHandler = context.getBean("calendarPackStagedMarginHandler", EventHandler::class.java)
        val activatedHandler: EventHandler = context.getBean("packActivatedHandler", EventHandler::class.java)
        private val name = "t20788-" + UUID.randomUUID()
        private val stagedReader = ListReader()
        private val activatedReader = ListReader()
        private var offset = 0L

        private fun consumer(
            subscription: ConsumerSubscription,
            reader: EventLogReader,
            handler: EventHandler,
        ) = JdbcInboxConsumer(
            ConsumerSubscription(name, subscription.topic, subscription.schema),
            catalogue,
            reader,
            scope,
            TenancyJooq.dslContext(OutboxSchema.jooqTables("submission"), tenants, runner),
            handler,
            RebuildRequiredSink { },
        )

        fun envelope(
            tenant: UUID,
            type: String,
            data: String,
        ) = Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/rules-svc",
            type = type,
            subject = "P-CAL",
            time = Instant.parse("2026-10-19T08:00:00Z"),
            tenant = TenantId(tenant),
            entity = EntityRef("E-001"),
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "rules-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = data,
        )

        /** Delivers the envelopes through the consumer of the topic, in order. */
        fun deliver(
            staged: Boolean,
            vararg envelopes: Envelope,
        ) = envelopes.map { e ->
            val reader = if (staged) stagedReader else activatedReader
            reader.records += LogRecord(++offset, json.write(e))
            val sub =
                if (staged) {
                    PackMarginConsumers.rulePackStagedSubscription()
                } else {
                    PackMarginConsumers.rulePackActivatedSubscription()
                }
            consumer(sub, reader, if (staged) stagedHandler else activatedHandler).pollOnce()
        }

        fun <T> asTenant(
            tenant: UUID,
            block: () -> T,
        ): T = tenants.bind(TenantId(tenant), actor) { runner.inTransaction(block) }

        fun rows(
            tenant: UUID,
            table: String,
        ): List<Record> = asTenant(tenant) { dsl.fetch(DSL.table(DSL.name("submission", table))).toList() }

        fun answer(
            tenant: UUID,
            date: String,
        ): PackMarginAnswer = asTenant(tenant) { margins.on(TenantId(tenant), LocalDate.parse(date)) }
    }

    private fun staged(
        id: String,
        version: String,
        margin: String?,
        type: String = "calendar",
    ) = """{"packIdentifier":"$id","packType":"$type","version":"$version","effectiveFrom":"2026-01-01",
        |"artefact":{"contents":{${margin?.let { "\"safetyMargin\":$it" } ?: ""}}}}
        """.trimMargin()

    private fun activated(
        version: String,
        date: String,
        id: String = "P-CAL",
    ) = """{"packIdentifier":"$id","version":"$version","effectiveFrom":"$date"}"""

    private fun run(
        dir: Path,
        block: Kit.() -> Unit,
    ) {
        SubmissionContext.online(dir).run { context -> Kit(context).block() }
    }

    private fun Kit.stagedAll(t: UUID): List<Envelope> =
        listOf(
            envelope(t, STAGED, staged("P-CAL", "1", """{"value":5,"unit":"DAYS"}""")),
            envelope(t, STAGED, staged("P-CAL", "2", """{"value":3,"unit":"DAYS"}""")),
            envelope(t, STAGED, staged("P-CAL", "3", null)),
            envelope(t, STAGED, staged("P-SCOPE", "1", """{"value":9,"unit":"DAYS"}""", type = "scope")),
            envelope(t, STAGED, staged("P-CAL", "4", """{"value":8785,"unit":"HOURS"}""")),
        )

    private fun Kit.activatedAll(t: UUID): List<Envelope> =
        listOf(
            envelope(t, ACTIVATED, activated("1", "2026-01-01")),
            envelope(t, ACTIVATED, activated("2", "2026-07-01")),
            envelope(t, ACTIVATED, activated("3", "2027-01-01")),
        )

    private fun Kit.expectAnswers(t: UUID) {
        answer(t, "2026-06-30") shouldBe PackMarginAnswer.Known(margin(5))
        answer(t, "2026-07-01") shouldBe PackMarginAnswer.Known(margin(3))
        answer(t, "2027-01-01") shouldBe PackMarginAnswer.Unknown
    }

    private fun margin(days: Long) =
        com.dynokore.gst.submission.application.pack.PackMargin
            .of(days, "DAYS")!!

    @Test
    fun `INV-AC-20786 both delivery orders give the same answers and a redelivery changes nothing`(
        @TempDir dir: Path,
    ) = run(dir) {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        deliver(true, *stagedAll(t1).toTypedArray())
        deliver(false, *activatedAll(t1).toTypedArray())
        deliver(false, *activatedAll(t2).toTypedArray())
        deliver(true, *stagedAll(t2).toTypedArray())
        expectAnswers(t1)
        expectAnswers(t2)

        fun snapshot(t: UUID) =
            listOf("calendar_pack_version", "calendar_pack_activation").map {
                rows(t, it)
                    .map { r ->
                        r.intoMap()
                    }.toSet()
            }
        val before = listOf(snapshot(t1), snapshot(t2))

        // a second delivery of the same payloads under new event ids, and a conflicting margin for version 1
        val conflicting = envelope(t1, STAGED, staged("P-CAL", "1", """{"value":7,"unit":"HOURS"}"""))
        deliver(true, *stagedAll(t1).toTypedArray(), conflicting)
        deliver(false, *activatedAll(t1).toTypedArray(), envelope(t1, ACTIVATED, activated("1", "2030-01-01")))
        deliver(true, *stagedAll(t2).toTypedArray())
        deliver(false, *activatedAll(t2).toTypedArray())

        listOf(snapshot(t1), snapshot(t2)) shouldBe before
        expectAnswers(t1)
        expectAnswers(t2)
        rows(t1, "calendar_pack_version").single { it["pack_version"] == "1" }.let {
            it["margin_value"] shouldBe 5
            it["margin_unit"] shouldBe MarginUnit.DAYS.name
        }
    }

    @Test
    fun `INV-AC-20786 the scope pack is not stored and an invalid margin is stored as none`(
        @TempDir dir: Path,
    ) = run(dir) {
        val t = UUID.randomUUID()
        deliver(true, *stagedAll(t).toTypedArray())
        val versions = rows(t, "calendar_pack_version")
        versions.map { it["pack_identifier"] to it["pack_version"] }.toSet() shouldBe
            setOf("P-CAL" to "1", "P-CAL" to "2", "P-CAL" to "3", "P-CAL" to "4")
        listOf("3", "4").forEach { v ->
            versions.single { it["pack_version"] == v }.let {
                it["margin_value"] shouldBe null
                it["margin_unit"] shouldBe null
            }
        }
    }

    @Test
    fun `INV-AC-20786 rows of one tenant are invisible to another and an unbound statement sees none`(
        @TempDir dir: Path,
    ) = run(dir) {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        deliver(true, *stagedAll(t1).toTypedArray())
        deliver(false, *activatedAll(t1).toTypedArray())
        deliver(true, envelope(t2, STAGED, staged("P-OTHER", "1", """{"value":1,"unit":"DAYS"}""")))
        rows(t2, "calendar_pack_version").map { it["pack_identifier"] }.toSet() shouldBe setOf("P-OTHER")
        rows(t2, "calendar_pack_activation") shouldBe emptyList()
        answer(t2, "2026-12-31") shouldBe PackMarginAnswer.Unknown
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).use { c ->
            listOf("calendar_pack_version", "calendar_pack_activation").forEach { table ->
                c.createStatement().use { st ->
                    st.executeQuery("select count(*) from submission.$table").use {
                        it.next()
                        it.getInt(1) shouldBe 0
                    }
                }
            }
        }
    }

    @Test
    fun `INV-AC-20786 a payload missing a required member is a ContractViolation and writes nothing`(
        @TempDir dir: Path,
    ) = run(dir) {
        val t = UUID.randomUUID()
        val bad =
            listOf(
                stagedHandler to envelope(t, STAGED, """{"packType":"calendar","version":"1"}"""),
                stagedHandler to envelope(t, STAGED, """{"packIdentifier":"P-CAL","packType":"calendar"}"""),
                activatedHandler to envelope(t, ACTIVATED, """{"version":"1","effectiveFrom":"2026-01-01"}"""),
                activatedHandler to envelope(t, ACTIVATED, """{"packIdentifier":"P","effectiveFrom":"2026-01-01"}"""),
                activatedHandler to envelope(t, ACTIVATED, """{"packIdentifier":"P-CAL","version":"1"}"""),
            )
        bad.forEach { (handler, e) -> shouldThrow<ContractViolation> { asTenant(t) { handler.handle(e) } } }
        rows(t, "calendar_pack_version") shouldBe emptyList()
        rows(t, "calendar_pack_activation") shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20786 the consumers subscribe to exactly the two pack topics and the catalogue lists both types`(
        @TempDir dir: Path,
    ) = run(dir) {
        PackMarginConsumers.rulePackStagedSubscription().topic shouldBe "gst.rules.rule-pack-staged.v1"
        PackMarginConsumers.rulePackActivatedSubscription().topic shouldBe "gst.rules.rule-pack-activated.v1"
        listOf("gst.rules.rule-pack-staged.v2", "gst.rules.rule-pack-activated.v1x").forEach { look ->
            (look == PackMarginConsumers.rulePackStagedSubscription().topic) shouldBe false
            (look == PackMarginConsumers.rulePackActivatedSubscription().topic) shouldBe false
        }
        val staged = checkNotNull(catalogue.find(STAGED))
        val activated = checkNotNull(catalogue.find(ACTIVATED))
        staged.topic shouldBe "gst.rules.rule-pack-staged.v1"
        activated.topic shouldBe "gst.rules.rule-pack-activated.v1"
        staged.producers shouldBe setOf("rules-svc")
        activated.producers shouldBe setOf("rules-svc")
        staged.retainedWhileResolvable shouldBe true
        activated.retainedWhileResolvable shouldBe false
    }

    private companion object {
        const val STAGED = "com.dynokore.gst.rules.RulePackStaged.v1"
        const val ACTIVATED = "com.dynokore.gst.rules.RulePackActivated.v1"
    }
}
