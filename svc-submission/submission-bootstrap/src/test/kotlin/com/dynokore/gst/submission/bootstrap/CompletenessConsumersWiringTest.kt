package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqCompletenessRows
import com.dynokore.gst.submission.adapters.persistence.JooqFeedControlStateStore
import com.dynokore.gst.submission.adapters.persistence.JooqWindowPartStateStore
import com.dynokore.gst.submission.application.completeness.AggregateWindowChangedHandler
import com.dynokore.gst.submission.application.completeness.CompletenessRows
import com.dynokore.gst.submission.application.completeness.FeedControlStates
import com.dynokore.gst.submission.application.completeness.FeedHealthChangedHandler
import com.dynokore.gst.submission.application.completeness.WindowPartStates
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Clock

/**
 * INV-AC-22206 (INV-REQ-792): the service built as TPL-18 writes it, over a lazy pool (no database), catalogues
 * `FeedHealthChanged` and `AggregateWindowChanged` as consumed events and declares a subscription, a handler and an
 * inbox consumer for each; the existing consumers keep their topics.
 */
class CompletenessConsumersWiringTest {
    private val feedType = SubmissionEvents.FEED_HEALTH_CHANGED_TYPE
    private val feedTopic = "gst.ingestion.feed-health-changed.v1"
    private val windowType = SubmissionEvents.AGGREGATE_WINDOW_CHANGED_TYPE
    private val windowTopic = "gst.aggregation.aggregate-window-changed.v1"
    private val config = CompletenessConsumers()

    private fun withContext(
        dir: Path,
        block: (ApplicationContext) -> Unit,
    ) {
        SubmissionContext.offline(dir).run { block(it) }
    }

    private fun feedConsumer(
        context: ApplicationContext,
        catalogue: EventCatalogue,
    ): JdbcInboxConsumer =
        config.feedHealthChangedConsumer(
            catalogue,
            context.getBean(EventLogReader::class.java),
            context.getBean(TenantScope::class.java),
            context.getBean("feedHealthChangedHandler", EventHandler::class.java),
            context.getBean(RebuildRequiredSink::class.java),
            Clock.systemUTC(),
        )

    private fun windowConsumer(
        context: ApplicationContext,
        catalogue: EventCatalogue,
    ): JdbcInboxConsumer =
        config.aggregateWindowChangedConsumer(
            catalogue,
            context.getBean(EventLogReader::class.java),
            context.getBean(TenantScope::class.java),
            context.getBean("aggregateWindowChangedHandler", EventHandler::class.java),
            context.getBean(RebuildRequiredSink::class.java),
            Clock.systemUTC(),
        )

    private fun without(
        context: ApplicationContext,
        type: String,
    ): List<CatalogueEntry> = context.getBean(EventCatalogue::class.java).entries().filter { it.eventType != type }

    @Test
    fun `INV-AC-22206 the catalogue lists both events on their topics and the existing topics are unchanged`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        val catalogue = context.getBean(EventCatalogue::class.java)
        catalogue.find(feedType)?.topic shouldBe feedTopic
        catalogue.find(feedType)?.producers shouldBe setOf("ingestion-svc")
        catalogue.find(feedType)?.contentTopic shouldBe false
        catalogue.find(windowType)?.topic shouldBe windowTopic
        catalogue.find(windowType)?.producers shouldBe setOf("aggregation-svc")
        catalogue.find(windowType)?.contentTopic shouldBe false
        SubmissionEvents.consumed.map { it.eventType to it.topic } shouldContainExactlyInAnyOrder
            listOf(
                "com.dynokore.gst.payload.PayloadReady.v1" to "gst.payload.payload-ready.v1",
                "com.dynokore.gst.tenancy.PeriodExtended.v1" to "gst.tenancy.period-extended.v1",
                "com.dynokore.gst.canonical.CanonicalVersionCreated.v1" to "gst.canonical.canonical-version-created.v1",
                "com.dynokore.gst.tenancy.PeriodDueDateChanged.v1" to "gst.tenancy.period-due-date-changed.v1",
                SubmissionEvents.PERIOD_RECORDS_CHANGED_TYPE to "gst.tenancy.period-records-changed.v1",
                SubmissionEvents.ENTITY_PROFILE_CHANGED_TYPE to SubmissionEvents.ENTITY_PROFILE_CHANGED_TOPIC,
                feedType to feedTopic,
                windowType to windowTopic,
                "com.dynokore.gst.canonical.DocumentHeld.v1" to "gst.canonical.document-held.v1",
                "com.dynokore.gst.canonical.DocumentReleased.v1" to "gst.canonical.document-released.v1",
                SubmissionEvents.RULE_PACK_STAGED_TYPE to "gst.rules.rule-pack-staged.v1",
                SubmissionEvents.RULE_PACK_ACTIVATED_TYPE to "gst.rules.rule-pack-activated.v1",
            )
    }

    @Test
    fun `INV-AC-22206 there is one subscription and one inbox consumer per new topic`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        listOf(
            CompletenessConsumers.feedHealthSubscription() to feedTopic,
            CompletenessConsumers.aggregateWindowSubscription() to windowTopic,
        ).forEach { (subscription, topic) ->
            subscription.topic shouldBe topic
            subscription.consumer shouldBe "submission-svc"
            subscription.schema shouldBe "submission"
        }
        val consumers = context.getBeansOfType(JdbcInboxConsumer::class.java).keys
        consumers.count { it == "feedHealthChangedConsumer" } shouldBe 1
        consumers.count { it == "aggregateWindowChangedConsumer" } shouldBe 1
        consumers shouldContain "periodRecordsChangedConsumer"
        consumers shouldContain "payloadReadyConsumer"
    }

    @Test
    fun `INV-AC-22206 the handler and store beans are the production ones`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        // product-runtime wraps every EventHandler bean in a tracing decorator, so only the bean type is asserted.
        context.getBean("feedHealthChangedHandler").shouldBeInstanceOf<EventHandler>()
        context.getBean("aggregateWindowChangedHandler").shouldBeInstanceOf<EventHandler>()
        config
            .feedHealthChangedHandler(
                context.getBean(TenantContext::class.java),
                { error("not called") },
                context.getBean(FeedControlStates::class.java),
            ).shouldBeInstanceOf<FeedHealthChangedHandler>()
        config
            .aggregateWindowChangedHandler(
                context.getBean(TenantContext::class.java),
                { error("not called") },
                context.getBean(WindowPartStates::class.java),
            ).shouldBeInstanceOf<AggregateWindowChangedHandler>()
        context.getBean(FeedControlStates::class.java).shouldBeInstanceOf<JooqFeedControlStateStore>()
        context.getBean(WindowPartStates::class.java).shouldBeInstanceOf<JooqWindowPartStateStore>()
        context.getBean(CompletenessRows::class.java).shouldBeInstanceOf<JooqCompletenessRows>()
    }

    @Test
    fun `INV-AC-22206 a catalogue that lists a type on another topic or not at all makes the consumer refuse to build`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        feedConsumer(context, context.getBean(EventCatalogue::class.java)) // the full catalogue builds
        windowConsumer(context, context.getBean(EventCatalogue::class.java))
        shouldThrow<IllegalStateException> { feedConsumer(context, EventCatalogue(without(context, feedType))) }
        shouldThrow<IllegalStateException> { windowConsumer(context, EventCatalogue(without(context, windowType))) }
        val feedElsewhere =
            CatalogueEntry(feedType, windowTopic, setOf("ingestion-svc"), false, retainedWhileResolvable = false)
        shouldThrow<IllegalStateException> {
            feedConsumer(context, EventCatalogue(without(context, feedType) + feedElsewhere))
        }
        val windowLookAlike =
            CatalogueEntry(
                windowType,
                "$windowTopic.x",
                setOf("aggregation-svc"),
                false,
                retainedWhileResolvable = false,
            )
        shouldThrow<IllegalStateException> {
            windowConsumer(context, EventCatalogue(without(context, windowType) + windowLookAlike))
        }
    }
}
