package com.dynokore.gst.submission.bootstrap

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

class ComplianceConfigurationTest {
    private val prefix = "inv.compliance-metrics"
    private val keys =
        mapOf(
            "many-versions-from" to "inv.compliance-metrics.many-versions-from",
            "high-at-or-above" to "inv.compliance-metrics.high-at-or-above",
            "trend-periods" to "inv.compliance-metrics.trend-periods",
        )

    private fun shipped(): Map<String, Any> {
        val source = YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml")).single()
        val names = (source as org.springframework.core.env.EnumerablePropertySource<*>).propertyNames
        return names.filter { it.startsWith("$prefix.") }.associateWith { source.getProperty(it)!! }
    }

    private fun bind(values: Map<String, Any>): ComplianceMetricsProperties {
        val source: ConfigurationPropertySource = MapConfigurationPropertySource(values)
        return Binder(source).bindOrCreate(prefix, Bindable.of(ComplianceMetricsProperties::class.java))
    }

    @Test
    fun `INV-AC-14179 thresholds come from the shipped yaml and a missing or zero value fails the start`() {
        val shipped = shipped()
        var bindings = 0

        val bound = bind(shipped).also { bindings++ }
        bound.manyVersionsFrom shouldBe 3
        bound.highAtOrAbove.numerator shouldBe 1
        bound.highAtOrAbove.denominator shouldBe 10
        bound.trendPeriods shouldBe 6

        keys.values.forEach { key ->
            val variant = shipped.filterKeys { it != key && !it.startsWith("$key.") }
            (variant.size < shipped.size) shouldBe true
            assertThrows<Exception> {
                bindings++
                bind(variant)
            }
        }
        listOf(
            "$prefix.many-versions-from",
            "$prefix.trend-periods",
            "$prefix.high-at-or-above.denominator",
        ).forEach { key ->
            assertThrows<Exception> {
                bindings++
                bind(shipped + (key to 0))
            }
        }
        bindings shouldBe 7
    }
}
