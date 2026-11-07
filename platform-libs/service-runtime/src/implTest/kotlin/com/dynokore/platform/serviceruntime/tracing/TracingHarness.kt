package com.dynokore.platform.serviceruntime.tracing

import com.dynokore.platform.serviceruntime.binding.BindingAutoConfiguration
import com.dynokore.platform.serviceruntime.client.InternalRestClientAutoConfiguration
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** A callee: records the `traceparent` header of each request and answers 200. */
class CalleeStub : AutoCloseable {
    val traceparents = CopyOnWriteArrayList<String?>()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                traceparents += exchange.requestHeaders.getFirst("traceparent")
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }
    val port: Int get() = server.address.port

    override fun close() = server.stop(0)
}

object TracingHarness {
    fun runner(
        meters: MeterRegistry = SimpleMeterRegistry(),
        properties: Map<String, Any>,
    ): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    BindingAutoConfiguration::class.java,
                    TracingAutoConfiguration::class.java,
                    InternalRestClientAutoConfiguration::class.java,
                ),
            ).withBean(MeterRegistry::class.java, { meters })
            .withInitializer {
                // implTest sets the endpoint as a JVM system property; only the given map counts here
                it.environment.propertySources.remove("systemProperties")
                it.environment.propertySources.addFirst(MapPropertySource("tracing-test", properties))
            }

    fun call(
        ctx: ApplicationContext,
        url: String,
    ) {
        ctx
            .getBean(
                "internalRestClientBuilder",
                RestClient.Builder::class.java,
            ).build()
            .get()
            .uri(url)
            .retrieve()
            .toBodilessEntity()
    }

    /** The spans of every /v1/traces body the collector received, once at least [count] spans arrived. */
    fun spans(
        collector: TraceCollectorStub,
        count: Int = 1,
    ): List<DecodedSpan> {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            val spans = collected(collector)
            if (spans.size >= count) return spans
            Thread.sleep(50)
        }
        return collected(collector)
    }

    /** Asserts path, content type and absent Content-Encoding of every request, then decodes the bodies. */
    fun collected(collector: TraceCollectorStub): List<DecodedSpan> =
        collector.received.flatMap {
            it.path shouldBe "/v1/traces"
            it.contentType shouldBe "application/x-protobuf"
            it.contentEncoding shouldBe null
            OtlpTraceReader.decode(it.body).spans
        }

    fun resourceAttribute(
        collector: TraceCollectorStub,
        key: String,
    ): String? = collector.received.firstNotNullOfOrNull { OtlpTraceReader.decode(it.body).resourceAttributes[key] }
}
