package com.dynokore.platform.serviceruntime.tracing

import com.dynokore.platform.serviceruntime.logging.TraceParent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class TraceContextTest {
    private val traceId = "0af7651916cd43dd8448eb211c80319c"
    private val parentId = "b7ad6b7169203331"

    private fun props(collector: TraceCollectorStub) =
        mapOf(
            "gst.service" to "svc-a",
            "gst.bindings.telemetry-export-otlp-endpoint" to collector.baseUrl,
            "management.tracing.sampling.probability" to "1.0",
        )

    /** The filter instance published by the auto-configuration's FilterRegistrationBean, not a hand-built one. */
    @Suppress("UNCHECKED_CAST")
    private fun wiredFilter(ctx: org.springframework.context.ApplicationContext): jakarta.servlet.Filter =
        ctx.getBean("traceContextCaptureFilter", FilterRegistrationBean::class.java).filter as jakarta.servlet.Filter

    /** Runs the capture filter on a request carrying [header] (or none), and makes the outbound call inside it. */
    private fun exchange(header: String?): Pair<DecodedSpan, String?> {
        TraceCollectorStub { 200 }.use { collector ->
            CalleeStub().use { callee ->
                var spanNode: DecodedSpan? = null
                TracingHarness.runner(properties = props(collector)).run { ctx ->
                    val request = MockHttpServletRequest().also { r -> header?.let { r.addHeader("traceparent", it) } }
                    val chain = FilterChain { _, _ -> TracingHarness.call(ctx, "http://localhost:${callee.port}/x") }
                    wiredFilter(ctx).doFilter(request, MockHttpServletResponse(), chain)
                    ctx.close()
                    spanNode = TracingHarness.spans(collector).single()
                }
                return spanNode!! to callee.traceparents.single()
            }
        }
    }

    @Test
    @DisplayName("INV-AC-17625 the outgoing traceparent equals the trace id, span id and flags of the exported span")
    fun `INV-AC-17625 outgoing header equals span`() {
        val (span, header) = exchange(null)
        header!!.substringBeforeLast('-') shouldBe
            "00-${span.traceId}-${span.spanId}"
        (header.substringAfterLast('-').toInt(16) and 1) shouldBe 1
        span.parentSpanId shouldBe ""
    }

    @Test
    @DisplayName("INV-AC-17625 an accepted inbound traceparent is the parent of the exported span")
    fun `INV-AC-17625 inbound parent`() {
        val (span, header) = exchange("00-$traceId-$parentId-01")
        span.traceId shouldBe traceId
        span.parentSpanId shouldBe parentId
        header!!.substringBeforeLast('-') shouldBe "00-$traceId-${span.spanId}"
    }

    @Test
    @DisplayName("INV-AC-17625 probe: an inbound traceparent with an all-zero trace id starts a new trace")
    fun `INV-AC-17625 all-zero trace id`() {
        val bad = "00-${"0".repeat(32)}-$parentId-01"
        TraceParent.parse(bad) shouldBe null
        val (span, _) = exchange(bad)
        span.traceId shouldNotBe "0".repeat(32)
        span.parentSpanId shouldBe ""
    }

    @Test
    @DisplayName("INV-AC-17625 an inbound traceparent of wrong length is ignored")
    fun `INV-AC-17625 wrong length`() {
        val (span, _) = exchange("00-${traceId.dropLast(2)}-$parentId-01")
        span.traceId shouldNotBe traceId.dropLast(2)
        span.parentSpanId shouldBe ""
    }

    @Test
    @DisplayName("INV-AC-17625 an inbound traceparent in upper-case hex is ignored")
    fun `INV-AC-17625 upper case`() {
        val (span, _) = exchange("00-${traceId.uppercase()}-$parentId-01")
        span.traceId shouldNotBe traceId
        span.parentSpanId shouldBe ""
    }

    @Test
    @DisplayName("INV-AC-17625 the inbound filter is registered for /* at its order, with the capture filter type")
    fun `INV-AC-17625 filter registration`() {
        TraceCollectorStub { 200 }.use { collector ->
            TracingHarness.runner(properties = props(collector)).run { ctx ->
                val registration = ctx.getBean("traceContextCaptureFilter", FilterRegistrationBean::class.java)
                (registration.filter is TraceContextCaptureFilter) shouldBe true
                registration.urlPatterns.toList() shouldBe listOf("/*")
                registration.order shouldBe -60
            }
        }
    }

    @Test
    @DisplayName("INV-AC-17625 the inbound filter bean is absent when the endpoint is unset")
    fun `INV-AC-17625 filter absent without endpoint`() {
        TraceCollectorStub { 200 }.use { collector ->
            val unset = props(collector) - "gst.bindings.telemetry-export-otlp-endpoint"
            TracingHarness.runner(properties = unset).run { ctx ->
                ctx.containsBean("traceContextCaptureFilter") shouldBe false
            }
        }
    }
}
