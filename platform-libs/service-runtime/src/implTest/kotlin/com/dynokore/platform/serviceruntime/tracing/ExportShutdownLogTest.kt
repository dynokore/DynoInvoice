package com.dynokore.platform.serviceruntime.tracing

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class ExportShutdownLogTest {
    private class Failing : SpanExporter {
        override fun export(spans: MutableCollection<SpanData>) = CompletableResultCode.ofFailure()

        override fun flush() = CompletableResultCode.ofSuccess()

        override fun shutdown() = CompletableResultCode.ofSuccess()
    }

    private fun logged(block: (CountingSpanExporter) -> Unit): Pair<List<ILoggingEvent>, Double> {
        val meters = SimpleMeterRegistry()
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val log = LoggerFactory.getLogger(CountingSpanExporter::class.java) as Logger
        log.addAppender(appender)
        try {
            block(CountingSpanExporter(Failing(), meters))
        } finally {
            log.detachAppender(appender)
        }
        return appender.list.toList() to meters.counter(CountingSpanExporter.FAILED_METER).count()
    }

    @Test
    @DisplayName("INV-AC-17626 a failed export is logged and counted while the context runs")
    fun `INV-AC-17626 failed export logged`() {
        val (events, count) = logged { it.export(mutableListOf()) }
        events shouldHaveSize 1
        events[0].level shouldBe Level.ERROR
        count shouldBe 1.0
    }

    @Test
    @DisplayName("INV-AC-17626 failed exports while closing are counted and summarised once at shutdown")
    fun `INV-AC-17626 failed export while closing`() {
        val (events, count) =
            logged {
                it.markClosing()
                it.export(mutableListOf())
                it.export(mutableListOf())
                it.shutdown()
                it.shutdown()
            }
        events shouldHaveSize 1
        events[0].level shouldBe Level.WARN
        events[0].formattedMessage shouldBe "TRACE_EXPORT_FAILED suppressed=2 during shutdown"
        count shouldBe 2.0
    }

    @Test
    @DisplayName("INV-AC-17626 the context listener is wired and the shutdown WARN appears once")
    fun `INV-AC-17626 context close logs one WARN`() {
        val meters = SimpleMeterRegistry()
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val log = LoggerFactory.getLogger(CountingSpanExporter::class.java) as Logger
        log.addAppender(appender)
        try {
            TraceCollectorStub { 500 }.use { collector ->
                CalleeStub().use { callee ->
                    TracingHarness
                        .runner(
                            meters,
                            mapOf(
                                "gst.service" to "svc-a",
                                "gst.bindings.telemetry-export-otlp-endpoint" to collector.baseUrl,
                                "gst.tracing.peers[0].host" to "localhost",
                                "gst.tracing.peers[0].service" to "canonical-svc",
                                // exported only by the final flush of the context close
                                "management.tracing.sampling.probability" to "1.0",
                            ),
                        ).run { ctx ->
                            TracingHarness.call(ctx, "http://localhost:${callee.port}/x")
                            ctx.close()
                        }
                }
            }
        } finally {
            log.detachAppender(appender)
        }
        appender.list.filter { it.level == Level.ERROR } shouldHaveSize 0
        val warns = appender.list.filter { it.level == Level.WARN }
        warns shouldHaveSize 1
        warns[0].formattedMessage shouldBe "TRACE_EXPORT_FAILED suppressed=1 during shutdown"
        meters.counter(CountingSpanExporter.FAILED_METER).count() shouldBe 1.0
    }
}
