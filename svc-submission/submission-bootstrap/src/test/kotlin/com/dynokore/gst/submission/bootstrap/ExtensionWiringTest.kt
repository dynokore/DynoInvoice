package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.extension.DueDateExtension
import com.dynokore.gst.submission.application.extension.DueDateExtensions
import com.dynokore.gst.submission.application.extension.ExtensionScopeKind
import com.dynokore.gst.submission.application.extension.ExtensionTarget
import com.dynokore.gst.submission.application.extension.PeriodExtendedData
import com.dynokore.gst.submission.application.extension.PeriodExtendedHandler
import com.dynokore.gst.submission.application.extension.PeriodExtendedScope
import com.dynokore.gst.submission.application.register.RegisterDueDate
import com.dynokore.gst.submission.application.register.RegisterDueDateReader
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-20597 (INV-REQ-81 behaviour 2): the service built as TPL-18 writes it, over a lazy pool (no database), lists
 * `PeriodExtended` as a consumed event and declares one subscription and one inbox consumer for its topic.
 */
class ExtensionWiringTest {
    private val topic = "gst.tenancy.period-extended.v1"
    private val type = "com.dynokore.gst.tenancy.PeriodExtended.v1"
    private val config = ExtensionConsumerConfiguration()

    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000020598"))

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = tenant

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private class Store : DueDateExtensions {
        val rows = mutableListOf<DueDateExtension>()

        override fun insert(
            tenant: TenantId,
            row: DueDateExtension,
        ): Boolean = rows.add(row)

        override fun forPeriod(
            tenant: TenantId,
            entityRef: String,
            periodRef: String,
        ): List<DueDateExtension> = rows
    }

    private fun withContext(
        dir: Path,
        block: (ApplicationContext) -> Unit,
    ) {
        SubmissionContext.offline(dir).run { block(it) }
    }

    private fun consumer(
        context: ApplicationContext,
        catalogue: EventCatalogue,
    ): JdbcInboxConsumer =
        config.periodExtendedConsumer(
            catalogue,
            context.getBean(EventLogReader::class.java),
            context.getBean(TenantScope::class.java),
            context.getBean("periodExtendedHandler", EventHandler::class.java),
            context.getBean(RebuildRequiredSink::class.java),
            Clock.systemUTC(),
        )

    private fun entry(
        eventType: String,
        eventTopic: String,
    ) = CatalogueEntry(
        eventType,
        eventTopic,
        setOf("tenancy-svc"),
        contentTopic = false,
        retainedWhileResolvable = false,
    )

    private fun without(context: ApplicationContext): List<CatalogueEntry> =
        context.getBean(EventCatalogue::class.java).entries().filter { it.eventType != type }

    @Test
    fun `INV-AC-20597 the catalogue lists PeriodExtended as a consumed event of tenancy-svc without a content topic`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        val found = context.getBean(EventCatalogue::class.java).find(type)
        found?.topic shouldBe topic
        found?.producers shouldBe setOf("tenancy-svc")
        found?.contentTopic shouldBe false
        found?.recordTopic shouldBe false
        SubmissionEvents.consumed.map { it.eventType }.contains(type) shouldBe true
        SubmissionEvents.entries.map { it.eventType }.contains(type) shouldBe false
    }

    @Test
    fun `INV-AC-20597 there is one subscription and one inbox consumer for the topic besides the existing ones`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        val subscription = ExtensionConsumerConfiguration.subscription()
        subscription.topic shouldBe topic
        subscription.consumer shouldBe "submission-svc"
        subscription.schema shouldBe "submission"
        context.getBeansOfType(ConsumerSubscription::class.java).values.map { it.topic } shouldBe
            listOf(SubmissionEvents.PAYLOAD_READY_TOPIC)
        context.getBeansOfType(JdbcInboxConsumer::class.java).keys shouldContainExactlyInAnyOrder
            listOf(
                "payloadReadyConsumer",
                "breakGlassSessionConsumer",
                "periodExtendedConsumer",
                "canonicalVersionCreatedConsumer",
                "periodDueDateChangedConsumer",
                "periodRecordsChangedConsumer",
            )
    }

    @Test
    fun `INV-AC-20597 the existing consumers keep their topics`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        context.getBeansOfType(ConsumerSubscription::class.java).values.map { it.topic } shouldBe
            listOf(SubmissionEvents.PAYLOAD_READY_TOPIC)
        SubmissionEvents.PAYLOAD_READY_TOPIC shouldBe "gst.payload.payload-ready.v1"
        BreakGlassFeed.TOPIC shouldBe "gst.platform.break-glass-session-changed.v1"
    }

    private fun envelope(): Envelope =
        Envelope(
            id = EventId("e-1"),
            source = "/gst/tenancy-svc",
            type = type,
            subject = "E",
            time = Instant.parse("2026-10-19T08:00:00Z"),
            tenant = tenant,
            entity = EntityRef("E"),
            docUuid = DocUuid(UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6")),
            transactionDate = LocalDate.parse("2026-03-20"),
            docSequence = 1,
            instanceId = "tenancy-svc-0",
            correlation = CorrelationId("c"),
            causation = CausationId("c"),
            dataJson = "{}",
        )

    @Test
    fun `INV-AC-20597 the handler bean is a PeriodExtendedHandler built over the RegisterDueDateReader bean`() {
        val asked = mutableListOf<Pair<String, String>>()
        val register =
            RegisterDueDateReader {
                _,
                e,
                p,
                ->
                asked += e to p
                listOf(RegisterDueDate("d1", LocalDate.of(2026, 4, 30)))
            }
        val store = Store()
        val data =
            PeriodExtendedData(
                "E",
                "P",
                ExtensionTarget.SUBMISSION_DUE_DATE,
                null,
                LocalDate.of(2026, 5, 14),
                PeriodExtendedScope(ExtensionScopeKind.PERIOD),
                "IRAS-1",
            )
        val handler: PeriodExtendedHandler = config.periodExtendedHandler(tenants, { data }, register, store)
        handler.handle(envelope()) shouldBe HandleOutcome.APPLIED
        asked shouldBe listOf("E" to "P")
        store.rows.single().originalDueDate shouldBe LocalDate.of(2026, 4, 30)
    }

    @Test
    fun `INV-AC-20597 the production beans are the Jooq register reader and extension store`(
        @TempDir dir: Path,
    ): Unit =
        withContext(dir) { context ->
            context.getBean(RegisterDueDateReader::class.java).javaClass.simpleName shouldBe "JooqRegisterDueDateReader"
            context.getBean(DueDateExtensions::class.java).javaClass.simpleName shouldBe "JooqDueDateExtensionStore"
        }

    @Test
    fun `INV-AC-20597 a catalogue without the entry makes the construction fail`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        consumer(context, context.getBean(EventCatalogue::class.java)) // the full catalogue builds
        shouldThrow<IllegalStateException> { consumer(context, EventCatalogue(without(context))) }
    }

    @Test
    fun `INV-AC-20597 the look-alike topic period-extended v2 is not accepted`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        val lookAlike = EventCatalogue(without(context) + entry(type, "gst.tenancy.period-extended.v2"))
        shouldThrow<IllegalStateException> { consumer(context, lookAlike) }
        val otherType = EventCatalogue(without(context) + entry("$type.x", topic))
        shouldThrow<IllegalStateException> { consumer(context, otherType) }
    }
}
