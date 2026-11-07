package com.dynokore.platform.serviceruntime.tracing

import com.dynokore.platform.serviceruntime.binding.ConfiguredTelemetryExport
import com.dynokore.platform.serviceruntime.binding.GstProperties
import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.http.client.ClientHttpRequestInterceptor
import java.time.Duration

/**
 * CLIENT spans and W3C trace context for the calls of `internalRestClientBuilder`. Present only when
 * `gst.bindings.telemetry-export-otlp-endpoint` is set: without it no exporter, interceptor or filter bean exists and
 * no network call is made. Sampling is `management.tracing.sampling.probability` (Spring Boot's name; default 0.1),
 * parent-based so a sampled or unsampled remote parent is honoured (W3C Trace Context).
 */
@AutoConfiguration
@ConditionalOnProperty(name = [ConfiguredTelemetryExport.ENDPOINT_PROPERTY])
@EnableConfigurationProperties(TracingProperties::class)
class TracingAutoConfiguration {
    @Bean
    fun gstTracerProvider(
        gst: GstProperties,
        environment: Environment,
        meters: ObjectProvider<MeterRegistry>,
    ): SdkTracerProvider {
        val endpoint = environment.getRequiredProperty(ConfiguredTelemetryExport.ENDPOINT_PROPERTY)
        val probability = environment.getProperty(SAMPLING_PROPERTY, Double::class.javaObjectType, DEFAULT_PROBABILITY)
        val processor =
            BatchSpanProcessor
                .builder(CountingSpanExporter(otlpExporter(endpoint), meters.ifAvailable))
                .setScheduleDelay(SCHEDULE_DELAY)
                .setExporterTimeout(EXPORT_TIMEOUT)
                .build()
        return SdkTracerProvider
            .builder()
            .setResource(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), gst.requireService())))
            .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(probability)))
            .addSpanProcessor(processor)
            .build()
    }

    /** The SDK OTLP/HTTP protobuf exporter (the specification default protocol), compression none, bounded by the timeout. */
    private fun otlpExporter(endpoint: String): OtlpHttpSpanExporter =
        OtlpHttpSpanExporter
            .builder()
            .setEndpoint(endpoint.trimEnd('/') + "/v1/traces")
            .setCompression("none")
            .setTimeout(EXPORT_TIMEOUT)
            .build()

    @Bean
    fun clientSpanInterceptor(
        provider: SdkTracerProvider,
        properties: TracingProperties,
    ): ClientHttpRequestInterceptor =
        ClientSpanInterceptor(provider.get("com.dynokore.platform.serviceruntime.tracing"), properties.peers)

    @Bean
    fun traceContextCaptureFilter(): FilterRegistrationBean<TraceContextCaptureFilter> =
        FilterRegistrationBean(TraceContextCaptureFilter()).apply {
            order = ORDER_TRACE_CONTEXT
            urlPatterns = listOf("/*")
        }

    companion object {
        private const val SAMPLING_PROPERTY = "management.tracing.sampling.probability"
        private const val DEFAULT_PROBABILITY = 0.1
        private const val ORDER_TRACE_CONTEXT = -60
        private val EXPORT_TIMEOUT: Duration = Duration.ofSeconds(3)
        private val SCHEDULE_DELAY: Duration = Duration.ofMillis(200)
    }
}
