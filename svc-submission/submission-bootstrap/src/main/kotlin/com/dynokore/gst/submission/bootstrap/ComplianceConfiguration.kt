package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqObligationRowSource
import com.dynokore.gst.submission.application.compliance.ComplianceMetricsConfig
import com.dynokore.gst.submission.application.compliance.ComplianceMetricsQuery
import com.dynokore.gst.submission.application.compliance.ObligationRowSource
import com.dynokore.gst.submission.domain.metrics.Ratio
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** `inv.compliance-metrics.*` of application.yaml: no default in code (thresholds are configuration). */
@ConfigurationProperties("inv.compliance-metrics")
data class ComplianceMetricsProperties(
    val manyVersionsFrom: Int,
    val highAtOrAbove: RatioProperty,
    val trendPeriods: Int,
) {
    init {
        // Spring Boot binds an absent Int as 0, so a missing key and a 0 both fail the start.
        require(manyVersionsFrom > 0) { "inv.compliance-metrics.many-versions-from must be greater than 0" }
        require(trendPeriods > 0) { "inv.compliance-metrics.trend-periods must be greater than 0" }
    }

    data class RatioProperty(
        val numerator: Int,
        val denominator: Int,
    ) {
        init {
            require(numerator > 0) { "inv.compliance-metrics.high-at-or-above.numerator must be greater than 0" }
            require(denominator > 0) { "inv.compliance-metrics.high-at-or-above.denominator must be greater than 0" }
        }
    }
}

/** TPL-18 wiring of the compliance-metrics read (INV-REQ-1069, INV-REQ-1070): row source, use case and its config. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ComplianceMetricsProperties::class)
class ComplianceConfiguration {
    @Bean
    fun obligationRowSource(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): ObligationRowSource = JooqObligationRowSource(dsl, runner)

    @Bean
    fun complianceMetrics(
        replica: AuthorityReplica,
        rows: ObligationRowSource,
        properties: ComplianceMetricsProperties,
    ): ComplianceMetricsQuery =
        ComplianceMetricsQuery(
            replica,
            rows,
            ComplianceMetricsConfig(
                properties.manyVersionsFrom,
                Ratio(properties.highAtOrAbove.numerator, properties.highAtOrAbove.denominator),
                properties.trendPeriods,
            ),
        )
}
