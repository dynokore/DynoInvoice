package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqPeriodDueDateStore
import com.dynokore.gst.submission.adapters.persistence.JooqRegisterEntryStore
import com.dynokore.gst.submission.application.register.CanonicalVersionCreatedHandler
import com.dynokore.gst.submission.application.register.DocumentKindResolver
import com.dynokore.gst.submission.application.register.DocumentTypeKindResolver
import com.dynokore.gst.submission.application.register.FilingDeadlineSource
import com.dynokore.gst.submission.application.register.PeriodDueDateDeadlineSource
import com.dynokore.gst.submission.application.register.PeriodDueDates
import com.dynokore.gst.submission.application.register.RegisterEntryStore
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Clock
import java.util.UUID

/**
 * INV-AC-20515 (INV-REQ-570): the service built as TPL-18 writes it, over a lazy pool (no database), lists
 * `CanonicalVersionCreated` and `PeriodDueDateChanged` as consumed events and declares a subscription, a handler and
 * an inbox consumer for each.
 */
class RegisterWriterWiringTest {
    private val canonicalTopic = "gst.canonical.canonical-version-created.v1"
    private val canonicalType = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1"
    private val dueDateTopic = "gst.tenancy.period-due-date-changed.v1"
    private val dueDateType = "com.dynokore.gst.tenancy.PeriodDueDateChanged.v1"
    private val config = RegisterWriterConfiguration()

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = TenantId(UUID.fromString("00000000-0000-0000-0000-000000020516"))

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private fun withContext(
        dir: Path,
        block: (ApplicationContext) -> Unit,
    ) {
        SubmissionContext.offline(dir).run { block(it) }
    }

    private fun canonicalConsumer(
        context: ApplicationContext,
        catalogue: EventCatalogue,
    ): JdbcInboxConsumer =
        config.canonicalVersionCreatedConsumer(
            catalogue,
            context.getBean(EventLogReader::class.java),
            context.getBean(TenantScope::class.java),
            context.getBean("canonicalVersionCreatedHandler", EventHandler::class.java),
            context.getBean(RebuildRequiredSink::class.java),
            Clock.systemUTC(),
        )

    private fun without(
        context: ApplicationContext,
        type: String,
    ): List<CatalogueEntry> = context.getBean(EventCatalogue::class.java).entries().filter { it.eventType != type }

    @Test
    fun `INV-AC-20515 the catalogue lists both events and the existing entries are unchanged`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        val catalogue = context.getBean(EventCatalogue::class.java)
        val canonical = catalogue.find(canonicalType)
        canonical?.topic shouldBe canonicalTopic
        canonical?.producers shouldBe setOf("canonical-svc")
        canonical?.contentTopic shouldBe true
        val dueDate = catalogue.find(dueDateType)
        dueDate?.topic shouldBe dueDateTopic
        dueDate?.producers shouldBe setOf("tenancy-svc")
        dueDate?.contentTopic shouldBe false
        SubmissionEvents.consumed.map { it.eventType to it.topic } shouldContainExactlyInAnyOrder
            listOf(
                "com.dynokore.gst.payload.PayloadReady.v1" to "gst.payload.payload-ready.v1",
                "com.dynokore.gst.tenancy.PeriodExtended.v1" to "gst.tenancy.period-extended.v1",
                canonicalType to canonicalTopic,
                dueDateType to dueDateTopic,
                SubmissionEvents.PERIOD_RECORDS_CHANGED_TYPE to SubmissionEvents.PERIOD_RECORDS_CHANGED_TOPIC,
            )
        SubmissionEvents.entries.map { it.eventType }.contains(canonicalType) shouldBe false
        SubmissionEvents.entries.map { it.eventType }.contains(dueDateType) shouldBe false
        SubmissionEvents.topicByType.size shouldBe 5
        SubmissionEvents.PAYLOAD_READY_TOPIC shouldBe "gst.payload.payload-ready.v1"
        BreakGlassFeed.TOPIC shouldBe "gst.platform.break-glass-session-changed.v1"
    }

    @Test
    fun `INV-AC-20515 there is one subscription and one inbox consumer per new topic besides the existing ones`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        listOf(
            RegisterWriterConfiguration.canonicalVersionCreatedSubscription() to canonicalTopic,
            RegisterWriterConfiguration.periodDueDateChangedSubscription() to dueDateTopic,
        ).forEach { (subscription: ConsumerSubscription, topic) ->
            subscription.topic shouldBe topic
            subscription.consumer shouldBe "submission-svc"
            subscription.schema shouldBe "submission"
        }
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
    fun `INV-AC-20515 the handler bean is a CanonicalVersionCreatedHandler over the production store and ports`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        val store = RegisterEntryStore { _, _ -> error("not called") }
        val dueDates = context.getBean(PeriodDueDates::class.java)
        val handler: CanonicalVersionCreatedHandler =
            config.canonicalVersionCreatedHandler(
                tenants,
                { error("not called") },
                config.filingDeadlineSource(dueDates),
                config.documentKindResolver(),
                store,
            )
        handler.shouldBeInstanceOf<CanonicalVersionCreatedHandler>()
        context.getBean(RegisterEntryStore::class.java).shouldBeInstanceOf<JooqRegisterEntryStore>()
        dueDates.shouldBeInstanceOf<JooqPeriodDueDateStore>()
        context.getBean(DocumentKindResolver::class.java).shouldBeInstanceOf<DocumentTypeKindResolver>()
        context.getBean(FilingDeadlineSource::class.java).shouldBeInstanceOf<PeriodDueDateDeadlineSource>()
    }

    @Test
    fun `INV-AC-20515 a catalogue without the entry makes the consumer fail and a look-alike topic is not accepted`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        canonicalConsumer(context, context.getBean(EventCatalogue::class.java)) // the full catalogue builds
        shouldThrow<IllegalStateException> {
            canonicalConsumer(context, EventCatalogue(without(context, canonicalType)))
        }
        val lookAlike =
            CatalogueEntry(
                canonicalType,
                "gst.canonical.canonical-version-created.v2",
                setOf("canonical-svc"),
                contentTopic = true,
                retainedWhileResolvable = false,
            )
        shouldThrow<IllegalStateException> {
            canonicalConsumer(context, EventCatalogue(without(context, canonicalType) + lookAlike))
        }
    }
}
