package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.ConsoleDb
import com.dynokore.gst.consoleBff.adapters.SignedBindings
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
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
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The PeriodRecordsChanged inbox consumer of the bootstrap context against a real database (INV-AC-21079): the real
 * subscription and handler beans, driven by the library `JdbcInboxConsumer` over a fixed list of records, as
 * [ExceptionConsumersTest] does. Rows are read as `console_bff_runtime` under a signed binding.
 */
class PeriodRecordsConsumerTest {
    private val json = EnvelopeJson()
    private val time = Instant.parse("2026-11-12T08:15:30Z")

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
        val context: ApplicationContext,
    ) {
        val subscription = context.getBean("periodRecordsSubscription", ConsumerSubscription::class.java)
        private val scope = context.getBean(TenantScope::class.java)
        private val catalogue = context.getBean(EventCatalogue::class.java)
        private val realHandler = context.getBean("periodRecordsChangedHandler", EventHandler::class.java)
        val calls = AtomicInteger()
        private val handler =
            object : EventHandler {
                override fun handle(event: Envelope): HandleOutcome {
                    calls.incrementAndGet()
                    return realHandler.handle(event)
                }
            }
        val name = "t21083-" + UUID.randomUUID()
        private val reader = ListReader()
        private var offset = 0L

        fun deliver(vararg envelopes: Envelope) =
            envelopes.map {
                reader.records += LogRecord(++offset, json.write(it))
                JdbcInboxConsumer(
                    ConsumerSubscription(name, subscription.topic, subscription.schema),
                    catalogue,
                    reader,
                    scope,
                    TenancyJooq.dslContext(
                        OutboxSchema.jooqTables(subscription.schema),
                        scope.tenants,
                        scope.transactions,
                    ),
                    handler,
                    RebuildRequiredSink { },
                ).pollOnce()
            }
    }

    private fun envelope(
        tenant: UUID,
        id: String,
        data: String,
    ) = Envelope(
        id = EventId(id),
        source = "/gst/tenancy-svc",
        type = TYPE,
        subject = "E-001",
        time = time,
        tenant = TenantId(tenant),
        entity = EntityRef("E-001"),
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "tenancy-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun records(filingDueDate: String) =
        """{"entityRef":"E-001","version":1,"periods":[{"periodRef":"2026-Q4","periodStart":"2026-10-01",""" +
            """"periodEnd":"2026-12-31","filingDueDate":$filingDueDate}]}"""

    /** `entity_ref, period_ref, due_date, due_date_version` rows, read as the runtime role bound to [tenant]. */
    private fun rows(tenant: UUID): List<List<String>> =
        ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            c.createStatement().use { st ->
                st
                    .executeQuery(
                        "select entity_ref, period_ref, due_date::text, due_date_version::text " +
                            "from console_bff.period_due_date order by entity_ref, period_ref",
                    ).use {
                        buildList { while (it.next()) add((1..4).map { i -> it.getString(i) }) }
                    }
            }
        }

    private fun inboxRows(
        tenant: UUID,
        consumer: String,
    ): Int =
        ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            c.prepareStatement("select count(*) from console_bff.inbox where consumer = ?").use {
                it.setString(1, consumer)
                it.executeQuery().use { r ->
                    r.next()
                    r.getInt(1)
                }
            }
        }

    private fun run(
        dir: Path,
        block: Kit.() -> Unit,
    ) {
        val runner = ConsoleContext.online(dir)
        Files.writeString(dir.resolve("tenancy/binding-key-current"), ConsoleDb.BINDING_SECRET)
        runner.run { context ->
            context.startupFailure shouldBe null
            Kit(context).block()
        }
    }

    @Test
    fun `INV-AC-21079 the subscription names the period records topic and the schema console_bff`(
        @TempDir dir: Path,
    ) = run(dir) {
        subscription.topic shouldBe "gst.tenancy.period-records-changed.v1"
        subscription.schema shouldBe "console_bff"
        subscription.consumer shouldBe "console-bff"
        listOf("gst.tenancy.period-due-date-changed.v1", "gst.tenancy.period-records-changed.v2").forEach {
            (it == subscription.topic) shouldBe false
        }
    }

    @Test
    fun `INV-AC-21079 the first delivery stores the initial date with version 0 under tenant A`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        deliver(envelope(a, "ev-pr-1", records("\"2027-01-31\"")))
        rows(a) shouldBe listOf(listOf("E-001", "2026-Q4", "2027-01-31", "0"))
        inboxRows(a, name) shouldBe 1
        rows(b) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21079 the duplicate delivery of the same event id changes nothing`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        val e = envelope(a, "ev-pr-2", records("\"2027-01-31\""))
        deliver(e, e)
        calls.get() shouldBe 1
        inboxRows(a, name) shouldBe 1
        rows(a) shouldBe listOf(listOf("E-001", "2026-Q4", "2027-01-31", "0"))
    }

    @Test
    fun `INV-AC-21079 the malformed filingDueDate envelope is dead-lettered and changes no row`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        val results =
            deliver(
                envelope(a, "ev-pr-3", records("\"not-a-date\"")),
                envelope(a, "ev-pr-4", records("null")),
            )
        results.map { it.deadLettered } shouldBe listOf(1, 1)
        results.map { it.total } shouldBe listOf(1, 1)
        results.map { it.applied } shouldBe listOf(0, 0)
        rows(a) shouldBe emptyList()
        calls.get() shouldBe 2
    }

    private companion object {
        const val TYPE = "com.dynokore.gst.tenancy.PeriodRecordsChanged.v1"
    }
}
