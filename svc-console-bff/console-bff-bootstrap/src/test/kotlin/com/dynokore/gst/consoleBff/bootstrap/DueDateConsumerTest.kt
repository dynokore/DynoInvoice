package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.ConsoleDb
import com.dynokore.gst.consoleBff.adapters.SignedBindings
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
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.LaneLimits
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.ReplayDelivery
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
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
 * The PeriodDueDateChanged inbox consumer of the bootstrap context against a real database (INV-AC-21078): the real
 * subscription and handler beans driven by the library `JdbcInboxConsumer` over a fixed list of records, as
 * [ExceptionConsumersTest] does. Rows are read as `console_bff_runtime` under a signed binding.
 */
class DueDateConsumerTest {
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
        val subscription = context.getBean("periodDueDateChangedSubscription", ConsumerSubscription::class.java)
        private val scope = context.getBean(TenantScope::class.java)
        private val catalogue = context.getBean(EventCatalogue::class.java)
        private val realHandler = context.getBean("periodDueDateChangedHandler", EventHandler::class.java)
        val calls = AtomicInteger()
        private val handler =
            object : EventHandler {
                override fun handle(event: Envelope): HandleOutcome {
                    calls.incrementAndGet()
                    return realHandler.handle(event)
                }
            }
        val name = "t21082-" + UUID.randomUUID()
        private val reader = ListReader()
        private var offset = 0L

        private fun consumer() =
            JdbcInboxConsumer(
                ConsumerSubscription(name, subscription.topic, subscription.schema),
                catalogue,
                reader,
                scope,
                TenancyJooq.dslContext(OutboxSchema.jooqTables(subscription.schema), scope.tenants, scope.transactions),
                handler,
                RebuildRequiredSink { },
            )

        fun deliver(vararg envelopes: Envelope) =
            envelopes.map {
                reader.records += LogRecord(++offset, json.write(it))
                consumer().pollOnce()
            }
    }

    private fun envelope(
        tenant: UUID,
        id: String,
        type: String,
        data: String,
    ) = Envelope(
        id = EventId(id),
        source = "/gst/test-svc",
        type = type,
        subject = "E-001",
        time = time,
        tenant = TenantId(tenant),
        entity = EntityRef("E-001"),
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun changed(
        tenant: UUID,
        id: String,
        newDate: String = "2027-02-28",
        version: String? = "2",
    ) = envelope(
        tenant,
        id,
        CHANGED,
        """{"entityRef":"E-001","period":"2026-Q4","oldFilingDueDate":"2027-01-31","newFilingDueDate":"$newDate",""" +
            (if (version == null) "" else """"dueDateVersion":$version,""") +
            """"cause":"EXTENSION"}""",
    )

    /** `entity/period/date/version` rows of the table, read as the runtime role bound to [tenant]. */
    private fun rows(tenant: UUID): List<String> =
        ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            c.createStatement().use { st ->
                st
                    .executeQuery(
                        "select entity_ref, period_ref, due_date, due_date_version from console_bff.period_due_date " +
                            "order by entity_ref, period_ref",
                    ).use {
                        buildList {
                            while (it.next()) {
                                add(
                                    "${it.getString(1)}/${it.getString(2)}/${it.getString(3)}/${it.getLong(4)}",
                                )
                            }
                        }
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
    fun `INV-AC-21078 the subscription names the topic period-due-date-changed and the schema console_bff`(
        @TempDir dir: Path,
    ) = run(dir) {
        subscription.topic shouldBe "gst.tenancy.period-due-date-changed.v1"
        subscription.schema shouldBe "console_bff"
        subscription.consumer shouldBe "console-bff"
        listOf("gst.tenancy.period-records-changed.v1", "gst.tenancy.period-due-date-changed.v2").forEach {
            (it == subscription.topic) shouldBe false
        }
    }

    @Test
    fun `INV-AC-21078 the delivery stores the due date under tenant A`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        deliver(changed(a, "ev-dd-1"))
        rows(a) shouldBe listOf("E-001/2026-Q4/2027-02-28/2")
    }

    @Test
    fun `INV-AC-21078 the duplicate delivery changes nothing`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        val e = changed(a, "ev-dd-2")
        deliver(e, e)
        calls.get() shouldBe 1
        rows(a) shouldBe listOf("E-001/2026-Q4/2027-02-28/2")
    }

    @Test
    fun `INV-AC-21078 the envelope without dueDateVersion is dead-lettered and changes no row`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        deliver(changed(a, "ev-dd-3"))
        val results = deliver(changed(a, "ev-dd-3b", newDate = "2027-03-31", version = null))
        results.single().deadLettered shouldBe 1
        rows(a) shouldBe listOf("E-001/2026-Q4/2027-02-28/2")
    }

    @Test
    fun `INV-AC-21078 tenant B reads no row of tenant A`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        deliver(changed(a, "ev-dd-4"))
        rows(a).size shouldBe 1
        rows(b) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21078 the catalogue lists PeriodDueDateChanged on its topic and no look-alike`(
        @TempDir dir: Path,
    ) = run(dir) {
        val catalogue = context.getBean(EventCatalogue::class.java)
        val entry = checkNotNull(catalogue.find(CHANGED))
        entry.topic shouldBe "gst.tenancy.period-due-date-changed.v1"
        entry.producers shouldBe setOf("tenancy-svc")
        entry.contentTopic shouldBe false
        entry.recordTopic shouldBe false
        entry.retainedWhileResolvable shouldBe false
        catalogue.find("com.dynokore.gst.tenancy.PeriodDueDateChanged.v2") shouldBe null
    }

    @Test
    fun `INV-AC-21078 the replay dispatch reaches the due-date handler only and the handler bean is an EventHandler`(
        @TempDir dir: Path,
    ) = run(dir) {
        (context.getBean("periodDueDateChangedHandler") is EventHandler) shouldBe true
        val calls = mutableListOf<String>()

        fun fake(name: String) =
            object : EventHandler {
                override fun handle(event: Envelope): HandleOutcome {
                    calls += name
                    return HandleOutcome.APPLIED
                }
            }
        val dispatch =
            replayDispatch(fake("held"), fake("released"), fake("masterData"), fake("dueDate"), fake("periodRecords"))
        val tenant = UUID.randomUUID()
        dispatch.handle(changed(tenant, "r1"))
        calls shouldBe listOf("dueDate")
        dispatch.handle(envelope(tenant, "r2", "com.dynokore.gst.canonical.DocumentHeld.v1", "{}"))
        calls shouldBe listOf("dueDate", "held")
        shouldThrow<ContractViolation> {
            dispatch.handle(envelope(tenant, "r3", "com.dynokore.gst.tenancy.PeriodDueDateChanged.v2", "{}"))
        }
        calls.size shouldBe 2
    }

    private companion object {
        const val CHANGED = "com.dynokore.gst.tenancy.PeriodDueDateChanged.v1"
    }
}
