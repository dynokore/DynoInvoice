package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqObligationStartDateStore
import com.dynokore.gst.submission.application.startdate.ObligationStartDates
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Clock

/**
 * INV-AC-22202 (INV-REQ-335): the service built as TPL-18 writes it, over a lazy pool (no database), lists
 * `EntityProfileChanged` as a consumed event and declares a subscription, a handler and one inbox consumer for it.
 */
class EntityProfileChangedWiringTest {
    private val topic = "gst.tenancy.entity-profile-changed.v1"
    private val type = "com.dynokore.gst.tenancy.EntityProfileChanged.v1"
    private val config = EntityProfileChangedConsumer()

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
        config.inboxConsumer(
            catalogue,
            context.getBean(EventLogReader::class.java),
            context.getBean(TenantScope::class.java),
            context.getBean("entityProfileChangedHandler", EventHandler::class.java),
            context.getBean(RebuildRequiredSink::class.java),
            Clock.systemUTC(),
        )

    private fun without(context: ApplicationContext): List<CatalogueEntry> =
        context.getBean(EventCatalogue::class.java).entries().filter { it.eventType != type }

    @Test
    fun `INV-AC-22202 the catalogue lists the event on its topic with producer tenancy-svc`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        val entry = context.getBean(EventCatalogue::class.java).find(type)
        entry?.topic shouldBe topic
        entry?.producers shouldBe setOf("tenancy-svc")
        entry?.contentTopic shouldBe false
        SubmissionEvents.ENTITY_PROFILE_CHANGED_TOPIC shouldBe topic
        SubmissionEvents.ENTITY_PROFILE_CHANGED_TYPE shouldBe type
        SubmissionEvents.entries.map { it.eventType }.contains(type) shouldBe false
    }

    @Test
    fun `INV-AC-22202 one subscription and one inbox consumer exist for the topic and the others are unchanged`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        val subscription = EntityProfileChangedConsumer.subscription()
        subscription.topic shouldBe topic
        subscription.consumer shouldBe "submission-svc"
        subscription.schema shouldBe "submission"
        val consumers = context.getBeansOfType(JdbcInboxConsumer::class.java)
        consumers.keys.filter { it.startsWith("entityProfile") } shouldBe listOf("entityProfileChangedConsumer")
        consumers.keys.size shouldBe 11
        val held = JdbcInboxConsumer::class.java.getDeclaredField("subscription").also { it.isAccessible = true }
        (held.get(consumers.getValue("entityProfileChangedConsumer")) as ConsumerSubscription).topic shouldBe topic
        PeriodRecordsConsumer.subscription().topic shouldBe "gst.tenancy.period-records-changed.v1"
        context.getBean("entityProfileChangedHandler").shouldBeInstanceOf<EventHandler>()
        context.getBean(JooqObligationStartDateStore::class.java).shouldBeInstanceOf<ObligationStartDates>()
    }

    @Test
    fun `INV-AC-22202 a catalogue listing the type on another topic or not at all makes the consumer refuse to build`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        consumer(context, context.getBean(EventCatalogue::class.java)) // the full catalogue builds
        shouldThrow<IllegalStateException> { consumer(context, EventCatalogue(without(context))) }
        val lookAlike =
            CatalogueEntry(
                type,
                "gst.tenancy.entity-profile-changed.v2",
                setOf("tenancy-svc"),
                contentTopic = false,
                retainedWhileResolvable = false,
            )
        shouldThrow<IllegalStateException> { consumer(context, EventCatalogue(without(context) + lookAlike)) }
    }
}
