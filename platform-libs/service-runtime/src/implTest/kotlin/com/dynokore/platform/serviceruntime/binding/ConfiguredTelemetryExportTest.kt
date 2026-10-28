package com.dynokore.platform.serviceruntime.binding

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/** INV-REQ-1384, INV-Q-11058: one `TelemetryExport`, selected by configuration (ARC-11). */
class ConfiguredTelemetryExportTest {
    private val selection = "gst.bindings.telemetry-export"
    private val endpoint = "gst.bindings.telemetry-export-otlp-endpoint"
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BindingAutoConfiguration::class.java))
            // The build sets the binding for implTest (INV-Q-11993); these tests assert an absent or invalid one.
            .withInitializer { context ->
                context.environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            }

    /** Property values kept verbatim: `withPropertyValues("k=v")` trims `v`, which would hide a trailing space. */
    private fun withVerbatim(values: Map<String, String>) =
        runner.withInitializer { context ->
            context.environment.propertySources.addFirst(MapPropertySource("verbatim", values))
        }

    private fun messages(failure: Throwable?): List<String> =
        generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()

    private fun refused(
        property: String,
        selectionValue: String,
        endpointValue: String?,
    ) {
        val values = listOfNotNull(selection to selectionValue, endpointValue?.let { endpoint to it }).toMap()
        withVerbatim(values).run { context ->
            val all = messages(context.startupFailure)
            all.any { it.contains(property) }.shouldBeTrue()
            if (endpointValue != null && endpointValue.isNotBlank()) {
                all.none { it.contains(endpointValue) }.shouldBeTrue()
            }
        }
    }

    @Test
    fun `the properties default to null so the auto-configuration provides nothing`() {
        val bindings = BindingsProperties()
        bindings.telemetryExport shouldBe null
        bindings.telemetryExportOtlpEndpoint shouldBe null
        bindings.eventBinding shouldBe "postgres"
        bindings.objectStore shouldBe "s3-compatible"
        runner.run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(TelemetryExport::class.java).isEmpty().shouldBeTrue()
        }
    }

    @Test
    fun `both names select the same class and the endpoint is returned unchanged`() {
        for (name in listOf("bundled-otel", "managed-observability")) {
            for (url in listOf("http://collector:4318", "https://otlp.example.com")) {
                runner.withPropertyValues("$selection=$name", "$endpoint=$url").run { context ->
                    context.startupFailure shouldBe null
                    val bean = context.getBean(TelemetryExport::class.java)
                    (bean is ConfiguredTelemetryExport).shouldBeTrue()
                    bean.otlpEndpoint() shouldBe url
                }
            }
        }
    }

    @Test
    fun `a selection other than the two names refuses the start`() {
        for (bad in listOf("Bundled-Otel", "bundled-otel ", "bundled_otel", "managed-observability2", "", "false")) {
            withVerbatim(mapOf(selection to bad, endpoint to "http://collector:4318")).run { context ->
                val all = messages(context.startupFailure)
                all.any { it.contains(selection) }.shouldBeTrue()
                all.none { it.contains("http://collector:4318") }.shouldBeTrue()
            }
        }
    }

    @Test
    fun `an unusable endpoint refuses the start without echoing it`() {
        for (
        bad in
        listOf(
            "ftp://h",
            "h:4318",
            "http:///x",
            "http://u:p@h",
            "http://h?x=1",
            "http://h?",
            "http://h#f",
            "http://h/",
            "http://h/a/",
            "http://h:99999999999999/x ",
            "http://a b",
        )
        ) {
            refused(endpoint, "bundled-otel", bad)
        }
        for (
        bad in
        listOf(
            "HTTP://h/",
            "HTTP://u:p@h",
            "HTTP://h?x=1",
            "HTTP:///x",
            "FTP://h",
            "htp://h",
            "httpss://h",
            "HTTP:h",
            "http\u017f://h",
        )
        ) {
            refused(endpoint, "bundled-otel", bad)
        }
        refused(endpoint, "bundled-otel", "")
        refused(endpoint, "bundled-otel", "   ")
        refused(endpoint, "managed-observability", null)
    }

    @Test
    fun `the scheme is compared case-insensitively and the endpoint is not rewritten`() {
        for (url in listOf("HTTP://collector:4318", "HTTPS://otlp.example.com", "Http://h")) {
            withVerbatim(mapOf(selection to "bundled-otel", endpoint to url)).run { context ->
                context.startupFailure shouldBe null
                context.getBean(TelemetryExport::class.java).otlpEndpoint() shouldBe url
            }
        }
    }

    @Test
    fun `a user bean wins and the platform class is not instantiated`() {
        runner
            .withUserConfiguration(UserTelemetry::class.java)
            .withPropertyValues("$selection=bundled-otel", "$endpoint=http://collector:4318")
            .run { context ->
                context.startupFailure shouldBe null
                context.getBean(TelemetryExport::class.java).otlpEndpoint() shouldBe "http://user:4318"
                context.getBeansOfType(ConfiguredTelemetryExport::class.java).isEmpty().shouldBeTrue()
                context.containsBean("telemetryExport").shouldBeTrue()
                (context.getBean("telemetryExport") is ConfiguredTelemetryExport).shouldBeFalse()
            }
    }

    @Configuration(proxyBeanMethods = false)
    class UserTelemetry {
        @Bean
        fun telemetryExport(): TelemetryExport = TelemetryExport { "http://user:4318" }
    }
}
