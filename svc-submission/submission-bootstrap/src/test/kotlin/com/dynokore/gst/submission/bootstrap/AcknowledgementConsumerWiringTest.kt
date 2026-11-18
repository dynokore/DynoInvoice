package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.queue.AcknowledgementReceivedHandler
import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.tracing.TracedEventHandler
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.io.File
import java.nio.file.Path
import java.time.Clock

/**
 * INV-AC-24271: the service built as TPL-18 writes it, over a lazy pool (no database), maps the transport topic and
 * the type `AcknowledgementReceived.v1` to the handler of INV-T-24274, and refuses a look-alike type.
 */
class AcknowledgementConsumerWiringTest {
    private val topic = "gst.transport.acknowledgement-received.v1"
    private val type = "com.dynokore.gst.transport.AcknowledgementReceived.v1"
    private val lookAlikeType = "com.dynokore.gst.transport.AcknowledgementReceived.v2"
    private val config = AcknowledgementConsumerConfiguration()

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
        config.acknowledgementReceivedConsumer(
            catalogue,
            context.getBean(EventLogReader::class.java),
            context.getBean(TenantScope::class.java),
            context.getBean("acknowledgementReceivedHandler", EventHandler::class.java),
            context.getBean(RebuildRequiredSink::class.java),
            Clock.systemUTC(),
        )

    private fun entry(
        eventType: String,
        eventTopic: String,
    ) = CatalogueEntry(
        eventType,
        eventTopic,
        setOf("transport-svc"),
        contentTopic = false,
        retainedWhileResolvable = false,
    )

    private fun without(context: ApplicationContext): List<CatalogueEntry> =
        context.getBean(EventCatalogue::class.java).entries().filter { it.eventType != type }

    private fun held(
        consumer: JdbcInboxConsumer,
        field: String,
    ): Any =
        JdbcInboxConsumer::class.java
            .getDeclaredField(field)
            .also { it.isAccessible = true }
            .get(consumer)

    @Test
    fun `INV-AC-24271 the topic and the type map to the handler of the handler task`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        val catalogue = context.getBean(EventCatalogue::class.java)
        catalogue.find(type)?.topic shouldBe topic
        catalogue.find(type)?.producers shouldBe setOf("transport-svc")
        SubmissionEvents.ACKNOWLEDGEMENT_RECEIVED_TOPIC shouldBe topic
        SubmissionEvents.ACKNOWLEDGEMENT_RECEIVED_TYPE shouldBe type
        val subscription = AcknowledgementConsumerConfiguration.subscription()
        subscription.topic shouldBe topic
        subscription.schema shouldBe "submission"
        val consumers = context.getBeansOfType(JdbcInboxConsumer::class.java)
        consumers.keys.filter { it.startsWith("acknowledgementReceived") } shouldBe
            listOf("acknowledgementReceivedConsumer")
        val consumer = consumers.getValue("acknowledgementReceivedConsumer")
        (held(consumer, "subscription") as ConsumerSubscription).topic shouldBe topic
        val handler = context.getBean("acknowledgementReceivedHandler")
        // the runtime wraps every EventHandler bean in a tracing decorator; the consumer is built from that bean
        handler.shouldBeInstanceOf<TracedEventHandler>()
        handler.delegate.shouldBeInstanceOf<AcknowledgementReceivedHandler>()
    }

    @Test
    fun `INV-AC-24271 the look-alike type v2 is not mapped and a catalogue with it alone refuses the consumer`(
        @TempDir dir: Path,
    ) = withContext(dir) { context ->
        val catalogue = context.getBean(EventCatalogue::class.java)
        catalogue.find(lookAlikeType) shouldBe null
        consumer(context, catalogue) // the full catalogue builds
        shouldThrow<IllegalStateException> { consumer(context, EventCatalogue(without(context))) }
        shouldThrow<IllegalStateException> {
            consumer(context, EventCatalogue(without(context) + entry(lookAlikeType, topic)))
        }
        shouldThrow<IllegalStateException> {
            val otherTopic = entry(type, "gst.transport.acknowledgement-received.v2")
            consumer(context, EventCatalogue(without(context) + otherTopic))
        }
    }

    @Test
    fun `INV-AC-24271 operations tsv names this task on the submission-acknowledged channel`() {
        // the module directory is the working directory of the test task; the table is at the repository root
        val lines = File("../operations.tsv").readLines().filter { it.contains("submission-acknowledged") }
        lines shouldBe listOf("channel\tgst.submission.submission-acknowledged.v1\tINV-T-24275")
    }
}
