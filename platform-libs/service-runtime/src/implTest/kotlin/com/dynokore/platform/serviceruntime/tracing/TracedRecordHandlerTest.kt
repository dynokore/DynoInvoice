package com.dynokore.platform.serviceruntime.tracing

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** INV-AC-24592: the tracing wrapper forwards the log record of the two-argument handle. */
class TracedRecordHandlerTest {
    private val type = "com.dynokore.gst.tenancy.PolicyChanged.v1"
    private val names =
        setOf("messaging.system", "messaging.operation.type", "messaging.destination.name", "messaging.message.id")

    private class RecordAware : EventHandler {
        var failure: Throwable? = null
        val seen = mutableListOf<LogRecord>()

        override fun handle(event: Envelope): HandleOutcome = error("the record-aware method is the one called")

        override fun handle(
            event: Envelope,
            record: LogRecord,
        ): HandleOutcome {
            seen += record
            failure?.let { throw it }
            return HandleOutcome.APPLIED
        }
    }

    private fun envelope() =
        Envelope(
            EventId(UUID.randomUUID().toString()),
            "/gst/tenancy-svc",
            type,
            "agg-1",
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

    private fun run(
        handler: RecordAware,
        block: (TraceCollectorStub, EventHandler) -> Unit,
    ) {
        TraceCollectorStub { 200 }.use { collector ->
            val props =
                mapOf(
                    "gst.service" to "svc-a",
                    "management.tracing.sampling.probability" to "1.0",
                    "gst.bindings.telemetry-export-otlp-endpoint" to collector.baseUrl,
                )
            TracingHarness
                .runner(properties = props)
                .withBean("serviceHandler", EventHandler::class.java, { handler })
                .run { ctx ->
                    block(collector, ctx.getBean(EventHandler::class.java))
                    ctx.close()
                }
        }
    }

    @Test
    @DisplayName("INV-AC-24592 the traced handler hands the same record instance to the delegate and ends one span")
    fun `INV-AC-24592 record forwarded under one span`() {
        val e = envelope()
        val record = LogRecord(42, """{"id":"${e.id.value}"}""")
        val inner = RecordAware()
        run(inner) { collector, traced ->
            traced.shouldBeInstanceOf<TracedEventHandler>()
            traced.handle(e, record) shouldBe HandleOutcome.APPLIED
            inner.seen.size shouldBe 1
            inner.seen.single() shouldBeSameInstanceAs record
            val span = TracingHarness.spans(collector).single()
            span.kind shouldBe 5
            span.statusCode shouldBe 0
            span.attributes.keys shouldBe names
            span.attributes.getValue("messaging.operation.type") shouldBe "process"
            span.attributes.getValue("messaging.system") shouldBe "gst-event-log"
            span.attributes.getValue("messaging.destination.name") shouldBe type
            span.attributes.getValue("messaging.message.id") shouldBe e.id.value
        }
    }

    @Test
    @DisplayName("INV-AC-24592 a throwing delegate ends the span in ERROR and the exception is rethrown unchanged")
    fun `INV-AC-24592 delegate throws`() {
        val inner = RecordAware()
        val boom = IllegalStateException("handler failure")
        inner.failure = boom
        run(inner) { collector, traced ->
            val thrown = shouldThrow<IllegalStateException> { traced.handle(envelope(), LogRecord(7, "{}")) }
            thrown shouldBeSameInstanceAs boom
            val span = TracingHarness.spans(collector).single()
            span.kind shouldBe 5
            span.statusCode shouldBe 2
        }
    }
}
