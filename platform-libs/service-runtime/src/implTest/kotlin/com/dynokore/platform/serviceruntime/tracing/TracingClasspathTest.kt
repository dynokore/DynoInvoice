package com.dynokore.platform.serviceruntime.tracing

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** INV-T-17627 (INV-Q-17607 A): the tracing bridge and the OTLP trace exporter are on the impl runtime classpath. */
class TracingClasspathTest {
    private fun load(name: String): Class<*> = Class.forName(name, false, javaClass.classLoader)

    @Test
    @DisplayName("INV-AC-17624 the Micrometer tracing OpenTelemetry bridge classes load by name")
    fun `INV-AC-17624 bridge loads`() {
        assertThat(load("io.micrometer.tracing.otel.bridge.OtelTracer")).isNotNull()
    }

    @Test
    @DisplayName("INV-AC-17624 the OTLP trace exporter classes load by name")
    fun `INV-AC-17624 exporter loads`() {
        assertThat(load("io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter")).isNotNull()
    }

    @Test
    @DisplayName("INV-AC-17624 a look-alike exporter class name does not load")
    fun `INV-AC-17624 look-alike does not load`() {
        assertThatThrownBy { load("io.opentelemetry.exporter.otlp.trace.OtlpHttpSpanExporterX") }
            .isInstanceOf(ClassNotFoundException::class.java)
    }
}
