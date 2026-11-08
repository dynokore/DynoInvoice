package com.dynokore.platform.serviceruntime.tracing

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventBinding
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class MessagingSpanTest {
    private val type = "com.dynokore.gst.tenancy.PolicyChanged.v1"
    private val names =
        setOf("messaging.system", "messaging.operation.type", "messaging.destination.name", "messaging.message.id")

    private class RecordingBinding : EventBinding {
        val published = mutableListOf<Envelope>()
        var failure: Throwable? = null

        override fun publish(
            topic: String,
            envelope: Envelope,
        ) {
            published += envelope
            failure?.let { throw it }
        }
    }

    private class RecordingHandler : EventHandler {
        var failure: Throwable? = null

        override fun handle(event: Envelope): HandleOutcome {
            failure?.let { throw it }
            return HandleOutcome.APPLIED
        }
    }

    private fun envelope(
        type: String = this.type,
        subject: String = "agg-1",
        id: String = UUID.randomUUID().toString(),
    ) = Envelope(
        EventId(id),
        "/gst/tenancy-svc",
        type,
        subject,
        Instant.parse("2026-10-12T02:00:00Z"),
        TenantId(UUID.fromString("0a000000-0000-0000-0000-00000000000a")),
        EntityRef("E-001"),
        null,
        null,
        null,
        "svc-1",
        CorrelationId("corr-1"),
        CausationId("cause-1"),
        """{"tin":"tin-999"}""",
    )

    /** An envelope whose type bypasses the constructor pattern, to prove the exporter does not trim or alter it. */
    private fun withType(
        e: Envelope,
        value: String,
    ): Envelope {
        Envelope::class.java
            .getDeclaredField("type")
            .apply { isAccessible = true }
            .set(e, value)
        return e
    }

    private fun run(
        binding: RecordingBinding = RecordingBinding(),
        handler: RecordingHandler = RecordingHandler(),
        endpointSet: Boolean = true,
        block: (TraceCollectorStub, EventBinding, EventHandler) -> Unit,
    ) {
        TraceCollectorStub { 200 }.use { collector ->
            val props =
                mapOf(
                    "gst.service" to "svc-a",
                    "management.tracing.sampling.probability" to "1.0",
                ) +
                    if (endpointSet) {
                        mapOf("gst.bindings.telemetry-export-otlp-endpoint" to collector.baseUrl)
                    } else {
                        emptyMap()
                    }
            TracingHarness
                .runner(properties = props)
                .withBean("serviceBinding", EventBinding::class.java, { binding })
                .withBean("serviceHandler", EventHandler::class.java, { handler })
                .run { ctx ->
                    block(
                        collector,
                        ctx.getBean(EventBinding::class.java),
                        ctx.getBean(EventHandler::class.java),
                    )
                    ctx.close()
                }
        }
    }

    @Test
    @DisplayName("INV-AC-17626 one PRODUCER span and one CONSUMER span with the four messaging attributes")
    fun `INV-AC-17626 producer and consumer spans`() {
        val e = envelope()
        run { collector, binding, handler ->
            binding.publish("topic", e)
            handler.handle(e)
            val spans = TracingHarness.spans(collector, 2)
            spans.size shouldBe 2
            val producer = spans.single { it.kind == 4 }
            val consumer = spans.single { it.kind == 5 }
            producer.attributes.keys shouldBe names
            consumer.attributes.keys shouldBe names
            producer.attributes.getValue("messaging.system") shouldBe "gst-event-log"
            producer.attributes.getValue("messaging.operation.type") shouldBe "publish"
            consumer.attributes.getValue("messaging.operation.type") shouldBe "process"
            consumer.attributes.getValue("messaging.system") shouldBe "gst-event-log"
            listOf(producer, consumer).forEach {
                it.attributes.getValue("messaging.destination.name") shouldBe type
                it.attributes.getValue("messaging.message.id") shouldBe e.id.value
                it.statusCode shouldBe 0
            }
            TracingHarness.resourceAttribute(collector, "service.name") shouldBe "svc-a"
        }
    }

    @Test
    @DisplayName("INV-AC-17626 service-defined binding and handler beans are decorated, contracts forward unchanged")
    fun `INV-AC-17626 decorated service beans`() {
        val inner = RecordingBinding()
        val e = envelope()
        run(binding = inner) { _, binding, handler ->
            binding.shouldBeInstanceOf<TracedEventBinding>()
            handler.shouldBeInstanceOf<TracedEventHandler>()
            binding.publish("topic", e)
            inner.published shouldBe listOf(e)
            handler.handle(e) shouldBe HandleOutcome.APPLIED
        }
    }

    @Test
    @DisplayName(
        "INV-AC-17626 probe: subject doc-123 and the other envelope fields are in no part of the exported body",
    )
    fun `INV-AC-17626 closed attributes`() {
        val e = envelope(subject = "doc-123")
        val forbidden = listOf("doc-123", "tin-999", "E-001", "corr-1", "cause-1", "0a000000", "/gst/tenancy-svc")
        run { collector, binding, handler ->
            binding.publish("topic", e)
            handler.handle(e)
            TracingHarness.spans(collector, 2).size shouldBe 2
            collector.received.forEach {
                val body = String(it.body, Charsets.ISO_8859_1)
                forbidden.forEach { s -> body shouldNotContain s }
            }
        }
    }

    @Test
    @DisplayName(
        "INV-AC-17626 probe: a handler that throws ends the CONSUMER span in error " +
            "and the exception reaches the caller",
    )
    fun `INV-AC-17626 handler throws`() {
        val handler = RecordingHandler()
        val boom = IllegalStateException("handler failure secret-77")
        handler.failure = boom
        run(handler = handler) { collector, _, traced ->
            val thrown = shouldThrow<IllegalStateException> { traced.handle(envelope()) }
            thrown shouldBeSameInstanceAs boom
            val span = TracingHarness.spans(collector).single()
            span.kind shouldBe 5
            span.statusCode shouldBe 2
            span.attributes.keys shouldBe names
            collector.received.forEach { String(it.body, Charsets.ISO_8859_1) shouldNotContain "secret-77" }
        }
    }

    @Test
    @DisplayName(
        "INV-AC-17626 probe: a publish that throws ends the PRODUCER span in error " +
            "and the exception reaches the caller",
    )
    fun `INV-AC-17626 publish throws`() {
        val inner = RecordingBinding()
        val boom = IllegalStateException("down")
        inner.failure = boom
        run(binding = inner) { collector, binding, _ ->
            shouldThrow<IllegalStateException> { binding.publish("topic", envelope()) } shouldBeSameInstanceAs boom
            val span = TracingHarness.spans(collector).single()
            span.kind shouldBe 4
            span.statusCode shouldBe 2
        }
    }

    @Test
    @DisplayName("INV-AC-17626 probe: an envelope type with a trailing space is exported with that exact value")
    fun `INV-AC-17626 type not trimmed`() {
        val padded = "PolicyChanged "
        val e = withType(envelope(), padded)
        run { collector, binding, handler ->
            binding.publish("topic", e)
            handler.handle(e)
            TracingHarness.spans(collector, 2).forEach {
                it.attributes.getValue("messaging.destination.name") shouldBe padded
            }
        }
    }

    @Test
    @DisplayName(
        "INV-AC-17626 probe: PRODUCER and CONSUMER spans of one destination carry " +
            "different ids for different envelopes",
    )
    fun `INV-AC-17626 different ids`() {
        val published = envelope(id = "id-published")
        val handled = envelope(id = "id-handled")
        run { collector, binding, handler ->
            binding.publish("topic", published)
            handler.handle(handled)
            val spans = TracingHarness.spans(collector, 2)
            val producer = spans.single { it.kind == 4 }
            val consumer = spans.single { it.kind == 5 }
            producer.attributes.getValue("messaging.destination.name") shouldBe
                consumer.attributes.getValue("messaging.destination.name")
            producer.attributes.getValue("messaging.message.id") shouldBe "id-published"
            consumer.attributes.getValue("messaging.message.id") shouldBe "id-handled"
            producer.attributes.getValue("messaging.message.id") shouldNotBe
                consumer.attributes.getValue("messaging.message.id")
        }
    }

    @Test
    @DisplayName("INV-AC-17626 probe: endpoint unset, the beans are the same objects and the stub receives nothing")
    fun `INV-AC-17626 endpoint unset`() {
        val binding = RecordingBinding()
        val handler = RecordingHandler()
        val e = envelope()
        run(binding, handler, endpointSet = false) { collector, b, h ->
            b shouldBeSameInstanceAs binding
            h shouldBeSameInstanceAs handler
            b.publish("topic", e)
            h.handle(e)
            Thread.sleep(500)
            collector.received.shouldBeEmpty()
        }
    }

    @Test
    @DisplayName("INV-AC-17626 probe: an already decorated bean is not decorated twice")
    fun `INV-AC-17626 no double decoration`() {
        val e = envelope()
        run { collector, binding, _ ->
            (binding as TracedEventBinding).delegate.shouldBeInstanceOf<RecordingBinding>()
            binding shouldNotBeSameInstanceAs binding.delegate
            binding.publish("topic", e)
            TracingHarness.spans(collector).size shouldBe 1
        }
    }
}
