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
 * The MasterDataResolved inbox consumer of the bootstrap context against a real database (INV-AC-21063): the real
 * subscription and handler beans driven by the library `JdbcInboxConsumer` over a fixed list of records, as
 * [ExceptionConsumersTest] does. Rows are read as `console_bff_runtime` under a signed binding.
 */
class MasterDataConsumerTest {
    private val json = EnvelopeJson()
    private val time = Instant.parse("2026-11-12T08:15:30Z")
    private val d1 = UUID.randomUUID()

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
        val subscription = context.getBean("masterDataResolvedSubscription", ConsumerSubscription::class.java)
        private val heldSubscription = context.getBean("documentHeldSubscription", ConsumerSubscription::class.java)
        private val scope = context.getBean(TenantScope::class.java)
        private val catalogue = context.getBean(EventCatalogue::class.java)
        private val heldHandler = context.getBean("documentHeldHandler", EventHandler::class.java)
        private val realHandler = context.getBean("masterDataResolvedHandler", EventHandler::class.java)
        val calls = AtomicInteger()
        private val handler =
            object : EventHandler {
                override fun handle(event: Envelope): HandleOutcome {
                    calls.incrementAndGet()
                    return realHandler.handle(event)
                }
            }
        val name = "t21065-" + UUID.randomUUID()
        private val heldReader = ListReader()
        private val resolvedReader = ListReader()
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

        fun resolved(vararg envelopes: Envelope) =
            envelopes.map {
                resolvedReader.records += LogRecord(++offset, json.write(it))
                consumer(subscription, resolvedReader, handler).pollOnce()
            }
    }

    private fun envelope(
        tenant: UUID,
        id: String,
        type: String,
        data: String,
        sequence: Long,
        source: String,
    ) = Envelope(
        id = EventId(id),
        source = source,
        type = type,
        subject = "doc-1",
        time = time,
        tenant = TenantId(tenant),
        entity = EntityRef("E-001"),
        docUuid = DocUuid(d1),
        transactionDate = null,
        docSequence = sequence,
        instanceId = "svc-0",
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
        """{"docUuid":"$d1","docsequence":1,"holdReasons":["MDM-001","MISSING_TIN"],"prescribedPeriodRef":"2026-Q4"}""",
        1,
        "/gst/canonical-svc",
    )

    private fun resolvedEvent(
        tenant: UUID,
        id: String,
        docUuids: String = """["$d1"]""",
    ) = envelope(
        tenant,
        id,
        RESOLVED,
        """{"partyRef":"P-1","resolvedIdentifiers":{"uen":"201912345A"},"docUuids":$docUuids}""",
        2,
        "/gst/masterdata-svc",
    )

    /** `code to state` rows of the table, read as the runtime role bound to [tenant]. */
    private fun rows(tenant: UUID): List<Pair<String, String>> =
        ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            c.createStatement().use { st ->
                st.executeQuery("select code, state from console_bff.exception order by code").use {
                    buildList { while (it.next()) add(it.getString(1) to it.getString(2)) }
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
    fun `INV-AC-21063 the subscription names the topic master-data-resolved and the schema console_bff`(
        @TempDir dir: Path,
    ) = run(dir) {
        subscription.topic shouldBe "gst.masterdata.master-data-resolved.v1"
        subscription.schema shouldBe "console_bff"
        subscription.consumer shouldBe "console-bff"
        listOf("gst.masterdata.master-data-resolved.v2", "gst.canonical.document-released.v1").forEach {
            (it == subscription.topic) shouldBe false
        }
    }

    @Test
    fun `INV-AC-21063 the delivery resolves the D1 row of code MDM-001 and leaves other codes open`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        held(heldEvent(a, "ev-held-1"))
        rows(a) shouldBe listOf("MDM-001" to "OPEN", "MISSING_TIN" to "OPEN")
        resolved(resolvedEvent(a, "ev-md-1"))
        rows(a) shouldBe listOf("MDM-001" to "RESOLVED", "MISSING_TIN" to "OPEN")
    }

    @Test
    fun `INV-AC-21063 the duplicate delivery changes nothing`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        held(heldEvent(a, "ev-held-2"))
        val e = resolvedEvent(a, "ev-md-2")
        resolved(e, e)
        calls.get() shouldBe 1
        rows(a) shouldBe listOf("MDM-001" to "RESOLVED", "MISSING_TIN" to "OPEN")
    }

    @Test
    fun `INV-AC-21063 the malformed envelope is dead-lettered and changes no row`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        held(heldEvent(a, "ev-held-3"))
        val results = resolved(resolvedEvent(a, "ev-md-3", """"not-an-array""""))
        results.single().deadLettered shouldBe 1
        rows(a) shouldBe listOf("MDM-001" to "OPEN", "MISSING_TIN" to "OPEN")
    }

    @Test
    fun `INV-AC-21063 tenant B reads no row of tenant A`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        held(heldEvent(a, "ev-held-4"))
        resolved(resolvedEvent(a, "ev-md-4"))
        rows(a).size shouldBe 2
        rows(b) shouldBe emptyList()
    }

    private companion object {
        const val HELD = "com.dynokore.gst.canonical.DocumentHeld.v1"
        const val RESOLVED = "com.dynokore.gst.masterdata.MasterDataResolved.v1"
    }
}
