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
 * The SubmissionFailed and PayloadRejected inbox consumers of the bootstrap context against a real database
 * (INV-AC-21058): the real subscription and handler beans driven by the library `JdbcInboxConsumer` over a fixed list
 * of records, as [MasterDataConsumerTest] does. Rows are read as `console_bff_runtime` under a signed binding.
 */
class FailureConsumersTest {
    private val json = EnvelopeJson()
    private val time = Instant.parse("2026-11-12T08:15:30Z")
    private val d1 = UUID.randomUUID()
    private val d2 = UUID.randomUUID()

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
        val failedSubscription = context.getBean("submissionFailedSubscription", ConsumerSubscription::class.java)
        val rejectedSubscription = context.getBean("payloadRejectedSubscription", ConsumerSubscription::class.java)
        private val scope = context.getBean(TenantScope::class.java)
        private val catalogue = context.getBean(EventCatalogue::class.java)
        private val realFailed = context.getBean("submissionFailedHandler", EventHandler::class.java)
        private val realRejected = context.getBean("payloadRejectedHandler", EventHandler::class.java)
        val calls = AtomicInteger()

        private fun counting(real: EventHandler) =
            object : EventHandler {
                override fun handle(event: Envelope): HandleOutcome {
                    calls.incrementAndGet()
                    return real.handle(event)
                }
            }

        private val name = "t21060-" + UUID.randomUUID()
        private val failedReader = ListReader()
        private val rejectedReader = ListReader()
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

        fun failed(vararg envelopes: Envelope) =
            envelopes.map {
                failedReader.records += LogRecord(++offset, json.write(it))
                consumer(failedSubscription, failedReader, counting(realFailed)).pollOnce()
            }

        fun rejected(vararg envelopes: Envelope) =
            envelopes.map {
                rejectedReader.records += LogRecord(++offset, json.write(it))
                consumer(rejectedSubscription, rejectedReader, counting(realRejected)).pollOnce()
            }
    }

    private fun envelope(
        tenant: UUID,
        id: String,
        type: String,
        doc: UUID,
        data: String,
    ) = Envelope(
        id = EventId(id),
        source = "/gst/test-svc",
        type = type,
        subject = "doc-1",
        time = time,
        tenant = TenantId(tenant),
        entity = EntityRef("E-001"),
        docUuid = DocUuid(doc),
        transactionDate = null,
        docSequence = if (doc == d1) 1L else 2L,
        instanceId = "svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = data,
    )

    private fun failedEvent(
        tenant: UUID,
        id: String,
    ) = envelope(
        tenant,
        id,
        FAILED,
        d1,
        """{"docUuid":"$d1","version":1,"failureClass":"SUB-REJECTED","retryDisposition":"NONE"}""",
    )

    private fun rejectedEvent(
        tenant: UUID,
        id: String,
        code: String = """"code":"PAY-001",""",
    ) = envelope(
        tenant,
        id,
        REJECTED,
        d2,
        """{"docUuid":"$d2","version":1,"docsequence":2,$code"validationTrace":[]}""",
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
    fun `INV-AC-21058 the subscriptions name their topic and the schema console_bff`(
        @TempDir dir: Path,
    ) = run(dir) {
        failedSubscription.topic shouldBe "gst.submission.submission-failed.v1"
        failedSubscription.schema shouldBe "console_bff"
        failedSubscription.consumer shouldBe "console-bff"
        rejectedSubscription.topic shouldBe "gst.payload.payload-rejected.v1"
        rejectedSubscription.schema shouldBe "console_bff"
        rejectedSubscription.consumer shouldBe "console-bff"
    }

    @Test
    fun `INV-AC-21058 the SubmissionFailed delivery opens a row under the failure class`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        failed(failedEvent(a, "ev-f-1"))
        rows(a) shouldBe listOf("SUB-REJECTED" to "OPEN")
    }

    @Test
    fun `INV-AC-21058 the PayloadRejected delivery opens a row under its code`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        rejected(rejectedEvent(a, "ev-r-1"))
        rows(a) shouldBe listOf("PAY-001" to "OPEN")
    }

    @Test
    fun `INV-AC-21058 the duplicate delivery adds no second row`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        val e = failedEvent(a, "ev-f-2")
        failed(e, e)
        calls.get() shouldBe 1
        rows(a) shouldBe listOf("SUB-REJECTED" to "OPEN")
    }

    @Test
    fun `INV-AC-21058 the envelope without a code is dead-lettered and writes no row`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        val results = rejected(rejectedEvent(a, "ev-r-3", code = ""))
        results.single().deadLettered shouldBe 1
        rows(a) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21058 tenant B reads no row of tenant A`(
        @TempDir dir: Path,
    ) = run(dir) {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        failed(failedEvent(a, "ev-f-4"))
        rejected(rejectedEvent(a, "ev-r-4"))
        rows(a) shouldBe listOf("PAY-001" to "OPEN", "SUB-REJECTED" to "OPEN")
        rows(b) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21058 the catalogue lists PayloadRejected and SubmissionFailed as topics yaml does`(
        @TempDir dir: Path,
    ) = run(dir) {
        val catalogue = context.getBean(EventCatalogue::class.java)
        val rejected = checkNotNull(catalogue.find(REJECTED))
        rejected.topic shouldBe "gst.payload.payload-rejected.v1"
        rejected.producers shouldBe setOf("payload-svc")
        rejected.contentTopic shouldBe true
        rejected.recordTopic shouldBe false
        rejected.retainedWhileResolvable shouldBe false
        val failed = checkNotNull(catalogue.find(FAILED))
        failed.topic shouldBe "gst.submission.submission-failed.v1"
        failed.producers shouldBe setOf("submission-svc")
        failed.contentTopic shouldBe false
        failed.recordTopic shouldBe false
        failed.retainedWhileResolvable shouldBe false
        catalogue.find("com.dynokore.gst.payload.PayloadRejected.v2") shouldBe null
        catalogue.find("com.dynokore.gst.submission.SubmissionFailed.v2") shouldBe null
        ConsoleEvents.consumed.map { it.eventType }.take(3) shouldBe
            listOf(
                "com.dynokore.gst.canonical.DocumentHeld.v1",
                "com.dynokore.gst.canonical.DocumentReleased.v1",
                "com.dynokore.gst.masterdata.MasterDataResolved.v1",
            )
    }

    @Test
    fun `INV-AC-21058 the replay dispatch reaches each failure handler for its own type only`(
        @TempDir dir: Path,
    ) = run(dir) {
        val calls = mutableListOf<String>()

        fun fake(name: String) =
            object : EventHandler {
                override fun handle(event: Envelope): HandleOutcome {
                    calls += name
                    return HandleOutcome.APPLIED
                }
            }
        val dispatch =
            replayDispatch(
                fake("held"),
                fake("released"),
                fake("masterData"),
                fake("dueDate"),
                fake("periodRecords"),
                fake("submissionFailed"),
                fake("payloadRejected"),
            )
        val tenant = UUID.randomUUID()
        dispatch.handle(failedEvent(tenant, "d1"))
        calls shouldBe listOf("submissionFailed")
        dispatch.handle(rejectedEvent(tenant, "d2"))
        calls shouldBe listOf("submissionFailed", "payloadRejected")
        listOf(
            "com.dynokore.gst.payload.PayloadRejected.v2",
            "com.dynokore.gst.submission.SubmissionFailed.v2",
        ).forEach {
            shouldThrow<ContractViolation> { dispatch.handle(envelope(tenant, "d3", it, d1, "{}")) }
        }
        calls.size shouldBe 2
    }

    private companion object {
        const val FAILED = "com.dynokore.gst.submission.SubmissionFailed.v1"
        const val REJECTED = "com.dynokore.gst.payload.PayloadRejected.v1"
    }
}
