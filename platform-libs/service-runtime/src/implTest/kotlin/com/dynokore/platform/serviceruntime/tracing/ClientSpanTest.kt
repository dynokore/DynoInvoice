package com.dynokore.platform.serviceruntime.tracing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient

class ClientSpanTest {
    private fun props(
        collector: TraceCollectorStub,
        host: String = "localhost",
        extra: Map<String, Any> = emptyMap(),
    ): Map<String, Any> =
        mapOf(
            "gst.service" to "svc-a",
            "gst.bindings.telemetry-export-otlp-endpoint" to collector.baseUrl,
            "gst.tracing.peers[0].host" to host,
            "gst.tracing.peers[0].service" to "canonical-svc",
            "management.tracing.sampling.probability" to "1.0",
        ) + extra

    private fun withCall(
        properties: (TraceCollectorStub) -> Map<String, Any>,
        path: String = "/x",
        block: (TraceCollectorStub, CalleeStub) -> Unit,
    ) {
        TraceCollectorStub { 200 }.use { collector ->
            CalleeStub().use { callee ->
                TracingHarness.runner(properties = properties(collector)).run { ctx ->
                    TracingHarness.call(ctx, "http://localhost:${callee.port}$path")
                    ctx.close()
                    block(collector, callee)
                }
            }
        }
    }

    @Test
    @DisplayName("INV-AC-17625 one CLIENT span, mapped peer.service, exactly four attributes")
    fun `INV-AC-17625 client span`() {
        withCall({ props(it) }) { collector, callee ->
            val spans = TracingHarness.spans(collector)
            spans.size shouldBe 1
            spans[0].kind shouldBe 3
            val attrs = spans[0].attributes
            attrs.keys shouldBe
                setOf("peer.service", "http.request.method", "http.response.status_code", "server.address")
            attrs.getValue("peer.service") shouldBe "canonical-svc"
            attrs.getValue("http.request.method") shouldBe "GET"
            attrs.getValue("server.address") shouldBe "localhost"
            attrs.getValue("http.response.status_code") shouldBe "200"
            TracingHarness.resourceAttribute(collector, "service.name") shouldBe "svc-a"
            callee.traceparents.size shouldBe 1
        }
    }

    @Test
    @DisplayName("INV-AC-17625 an unmapped host is exported as peer.service equal to the host")
    fun `INV-AC-17625 unmapped host`() {
        withCall({ props(it, host = "other-host") }) { collector, _ ->
            val attrs = TracingHarness.spans(collector)[0].attributes
            attrs.getValue("peer.service") shouldBe "localhost"
        }
    }

    @Test
    @DisplayName("INV-AC-17625 probe: a peer host differing in case is not matched")
    fun `INV-AC-17625 case differs`() {
        withCall({ props(it, host = "LOCALHOST") }) { collector, _ ->
            val attrs = TracingHarness.spans(collector)[0].attributes
            attrs.getValue("peer.service") shouldBe "localhost"
        }
    }

    @Test
    @DisplayName("INV-AC-17625 probe: a peer host with a trailing space is not matched")
    fun `INV-AC-17625 trailing space`() {
        withCall({ props(it, host = "localhost ") }) { collector, _ ->
            val attrs = TracingHarness.spans(collector)[0].attributes
            attrs.getValue("peer.service") shouldBe "localhost"
        }
    }

    @Test
    @DisplayName("INV-AC-17625 probe: the query tin=123 appears nowhere in the exported body")
    fun `INV-AC-17625 query not exported`() {
        withCall({ props(it) }, path = "/x?tin=123") { collector, _ ->
            TracingHarness.spans(collector).size shouldBe 1
            collector.received.forEach { String(it.body, Charsets.ISO_8859_1) shouldNotContain "tin=123" }
        }
    }

    @Test
    @DisplayName("INV-AC-17625 probe: endpoint unset, no exporter bean, stub receives nothing")
    fun `INV-AC-17625 endpoint unset`() {
        TraceCollectorStub { 200 }.use { collector ->
            CalleeStub().use { callee ->
                val unset = props(collector) - "gst.bindings.telemetry-export-otlp-endpoint"
                TracingHarness.runner(properties = unset).run { ctx ->
                    ctx.getBeanNamesForType(io.opentelemetry.sdk.trace.SdkTracerProvider::class.java).size shouldBe 0
                    TracingHarness.call(ctx, "http://localhost:${callee.port}/x")
                    callee.traceparents.single() shouldBe null
                }
                Thread.sleep(500)
                collector.received.shouldBeEmpty()
            }
        }
    }

    @Test
    @DisplayName("INV-AC-17625 collector answers 500: call result kept, failure counted")
    fun `INV-AC-17625 collector 500`() {
        val meters = SimpleMeterRegistry()
        TraceCollectorStub { 500 }.use { collector ->
            CalleeStub().use { callee ->
                TracingHarness.runner(meters = meters, properties = props(collector)).run { ctx ->
                    val status =
                        ctx
                            .getBean("internalRestClientBuilder", RestClient.Builder::class.java)
                            .build()
                            .get()
                            .uri("http://localhost:${callee.port}/x")
                            .retrieve()
                            .toBodilessEntity()
                            .statusCode
                            .value()
                    status shouldBe 200
                    awaitCount(meters)
                    meters.counter(CountingSpanExporter.FAILED_METER).count() shouldBe 1.0
                }
            }
        }
    }

    @Test
    @DisplayName("INV-AC-17625 collector never answers: call result kept, failure counted")
    fun `INV-AC-17625 collector silent`() {
        val meters = SimpleMeterRegistry()
        TraceCollectorStub { null }.use { collector ->
            CalleeStub().use { callee ->
                TracingHarness.runner(meters = meters, properties = props(collector)).run { ctx ->
                    TracingHarness.call(ctx, "http://localhost:${callee.port}/x")
                    awaitCount(meters)
                    meters.counter(CountingSpanExporter.FAILED_METER).count() shouldBe 1.0
                }
            }
        }
    }

    @Test
    @DisplayName("INV-AC-17625 probe: a truncated protobuf body makes the test-only reader throw")
    fun `INV-AC-17625 truncated body`() {
        withCall({ props(it) }) { collector, _ ->
            TracingHarness.spans(collector)
            val body = collector.received.first().body
            OtlpTraceReader.decode(body).spans.size shouldBe 1
            for (cut in listOf(1, 5, body.size / 2, body.size - 1)) {
                shouldThrow<IllegalArgumentException> { OtlpTraceReader.decode(body.copyOf(body.size - cut)) }
            }
        }
    }

    private fun awaitCount(meters: SimpleMeterRegistry) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline && meters.counter(CountingSpanExporter.FAILED_METER).count() < 1.0) {
            Thread.sleep(50)
        }
    }
}
