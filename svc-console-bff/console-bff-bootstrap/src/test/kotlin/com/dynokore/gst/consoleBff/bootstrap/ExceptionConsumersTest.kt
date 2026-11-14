package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.ConsoleDb
import com.dynokore.gst.consoleBff.adapters.SignedBindings
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.ContractViolation
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
 * The DocumentHeld and DocumentReleased inbox consumers of the bootstrap context against a real database
 * (INV-AC-21028): the real subscription and handler beans, driven by the library `JdbcInboxConsumer` over a fixed
 * list of records, as svc-submission's PackMarginConsumersDbTest does. Rows are read as `console_bff_runtime` under
 * a signed binding.
 */
class ExceptionConsumersTest {
    private val json = EnvelopeJson()
    private val time = Instant.parse("2026-11-12T08:15:30Z")
    private val docUuid = UUID.randomUUID()

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
        val heldSubscription = context.getBean("documentHeldSubscription", ConsumerSubscription::class.java)
        val releasedSubscription = context.getBean("documentReleasedSubscription", ConsumerSubscription::class.java)
        private val scope = context.getBean(TenantScope::class.java)
        private val catalogue = context.getBean(EventCatalogue::class.java)
        private val realHeldHandler = context.getBean("documentHeldHandler", EventHandler::class.java)
        val heldCalls = AtomicInteger()
        private val heldHandler =
            object : EventHandler {
                override fun handle(event: Envelope): HandleOutcome {
                    heldCalls.incrementAndGet()
                    return realHeldHandler.handle(event)
                }
            }
        private val releasedHandler = context.getBean("documentReleasedHandler", EventHandler::class.java)
        val name = "t21031-" + UUID.randomUUID()
        private val heldReader = ListReader()
        private val releasedReader = ListReader()
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
            TenancyJooq.dslContext(OutboxSchema.jooqTables(subscription.schema), scope.tenants, scope.transactions),
            handler,
            RebuildRequiredSink { },
        )

        fun held(vararg envelopes: Envelope) =
            envelopes.map {
                heldReader.records += LogRecord(++offset, json.write(it))
                consumer(heldSubscription, heldReader, heldHandler).pollOnce()
            }

        fun released(vararg envelopes: Envelope) =
            envelopes.map {
                releasedReader.records += LogRecord(++offset, json.write(it))
                consumer(releasedSubscription, releasedReader, releasedHandler).pollOnce()
            }
    }

    private fun envelope(
        tenant: UUID,
        id: String,
        type: String,
        data: String,
        sequence: Long = 1,
    ) = Envelope(
        id = EventId(id),
        source = "/gst/canonical-svc",
        type = type,
        subject = "doc-1",
        time = time,
        tenant = TenantId(tenant),
        entity = EntityRef("E-001"),
        docUuid = DocUuid(docUuid),
        transactionDate = null,
        docSequence = sequence,
        instanceId = "canonical-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun heldEvent(
        tenant: UUID,
        id: String,
    ) = envelope(
        tenant,
        id,
        HELD,
        """{"docUuid":"$docUuid","docsequence":1,"holdReasons":["MISSING_TIN","BAD_UEN"],""" +
            """"prescribedPeriodRef":"2026-Q4"}""",
    )

    private fun releasedEvent(
        tenant: UUID,
        id: String,
        code: String,
    ) = envelope(tenant, id, RELEASED, """{"docUuid":"$docUuid","docsequence":2,"releasedHoldReasons":["$code"]}""", 2)

    /** `code to state to raised_at` rows of the table, read as the runtime role bound to [tenant]. */
    private fun rows(tenant: UUID): List<Triple<String, String, Instant>> =
        ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            c.createStatement().use { st ->
                st.executeQuery("select code, state, raised_at from console_bff.exception order by code").use {
                    buildList {
                        while (it.next()) add(Triple(it.getString(1), it.getString(2), it.getTimestamp(3).toInstant()))
                    }
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
        // the database installed the binding key of ConsoleDb; the context must sign with the same key
        Files.writeString(dir.resolve("tenancy/binding-key-current"), ConsoleDb.BINDING_SECRET)
        runner.run { context ->
            context.startupFailure shouldBe null
            Kit(context).block()
        }
    }

    @Test
    fun `INV-AC-21028 there is one subscription per topic in the schema console_bff`(
        @TempDir dir: Path,
    ) = run(dir) {
        heldSubscription.topic shouldBe "gst.canonical.document-held.v1"
        heldSubscription.schema shouldBe "console_bff"
        releasedSubscription.topic shouldBe "gst.canonical.document-released.v1"
        releasedSubscription.schema shouldBe "console_bff"
        heldSubscription.consumer shouldBe "console-bff"
        releasedSubscription.consumer shouldBe "console-bff"
        listOf("gst.canonical.document-held.v2", "gst.canonical.document-released.v1x").forEach { look ->
            (look == heldSubscription.topic || look == releasedSubscription.topic) shouldBe false
        }
    }

    @Test
    fun `INV-AC-21028 the catalogue lists exactly the two consumed events as topics yaml does`(
        @TempDir dir: Path,
    ) = run(dir) {
        val catalogue = context.getBean(EventCatalogue::class.java)
        val held = checkNotNull(catalogue.find("com.dynokore.gst.canonical.DocumentHeld.v1"))
        val released = checkNotNull(catalogue.find("com.dynokore.gst.canonical.DocumentReleased.v1"))
        held.topic shouldBe "gst.canonical.document-held.v1"
        released.topic shouldBe "gst.canonical.document-released.v1"
        listOf(held, released).forEach {
            it.producers shouldBe setOf("canonical-svc")
            it.contentTopic shouldBe false
            it.recordTopic shouldBe false
            it.retainedWhileResolvable shouldBe false
        }
        ConsoleEvents.consumed.map { it.eventType } shouldBe listOf(held.eventType, released.eventType)
    }

    @Test
    fun `INV-AC-21028 the lane limits bean sets no rate and one work queue exists`(
        @TempDir dir: Path,
    ) = run(dir) {
        context.getBeansOfType(LaneLimits::class.java).keys shouldBe setOf("laneLimits")
        val limits = context.getBean(LaneLimits::class.java)
        Lane.entries.forEach { limits.of(it) shouldBe null }
        context.getBeansOfType(WorkQueue::class.java).size shouldBe 1
    }

    @Test
    fun `INV-AC-21028 the replay dispatch reaches one handler by type and refuses any other type`(
        @TempDir dir: Path,
    ) = run(dir) {
        context.getBeansOfType(ReplayDelivery::class.java).keys shouldBe setOf("consoleBffReplayDelivery")
        val calls = mutableListOf<String>()

        fun fake(name: String) =
            object : EventHandler {
                override fun handle(event: Envelope): HandleOutcome {
                    calls += name
                    return HandleOutcome.APPLIED
                }
            }
        val dispatch = replayDispatch(fake("held"), fake("released"))
        val tenant = UUID.randomUUID()
        dispatch.handle(heldEvent(tenant, "r1"))
        calls shouldBe listOf("held")
        dispatch.handle(releasedEvent(tenant, "r2", "X"))
        calls shouldBe listOf("held", "released")
        shouldThrow<ContractViolation> {
            dispatch.handle(envelope(tenant, "r3", "com.dynokore.gst.canonical.DocumentHeld.v2", "{}"))
        }
        shouldThrow<ContractViolation> {
            dispatch.handle(envelope(tenant, "r4", "com.dynokore.gst.canonical.Unknown.v1", "{}"))
        }
        calls shouldBe listOf("held", "released")
    }

    @Test
    fun `INV-AC-21028 the held delivery writes two OPEN rows with raised_at of the envelope time`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        held(heldEvent(a, "ev-held-1"))
        rows(a) shouldBe listOf(Triple("BAD_UEN", "OPEN", time), Triple("MISSING_TIN", "OPEN", time))
        inboxRows(a, name) shouldBe 1
    }

    @Test
    fun `INV-AC-21028 the duplicate delivery of the same event id writes nothing new`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        val e = heldEvent(a, "ev-held-2")
        held(e, e)
        heldCalls.get() shouldBe 1
        inboxRows(a, name) shouldBe 1
        rows(a).size shouldBe 2
    }

    @Test
    fun `INV-AC-21028 the release resolves exactly the released code`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        held(heldEvent(a, "ev-held-3"))
        released(releasedEvent(a, "ev-rel-3", "MISSING_TIN"))
        rows(a) shouldBe listOf(Triple("BAD_UEN", "OPEN", time), Triple("MISSING_TIN", "RESOLVED", time))
    }

    @Test
    fun `INV-AC-21028 tenant B reads no row of tenant A`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        held(heldEvent(a, "ev-held-4"))
        rows(a).size shouldBe 2
        rows(b) shouldBe emptyList()
        inboxRows(b, name) shouldBe 0
    }

    private companion object {
        const val HELD = "com.dynokore.gst.canonical.DocumentHeld.v1"
        const val RELEASED = "com.dynokore.gst.canonical.DocumentReleased.v1"
    }
}
