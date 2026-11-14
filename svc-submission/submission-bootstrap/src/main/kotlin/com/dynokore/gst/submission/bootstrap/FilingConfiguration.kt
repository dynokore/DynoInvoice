package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqFilingDueDates
import com.dynokore.gst.submission.adapters.persistence.ReadinessLatestChecks
import com.dynokore.gst.submission.adapters.policy.PolicyGateModes
import com.dynokore.gst.submission.application.filing.ConfirmFiling
import com.dynokore.gst.submission.application.filing.FilingDueDates
import com.dynokore.gst.submission.application.filing.FilingFactStore
import com.dynokore.gst.submission.application.filing.GateModesAt
import com.dynokore.gst.submission.application.gate.LatestChecks
import com.dynokore.gst.submission.application.readiness.ReadinessCheckReader
import com.dynokore.gst.submission.application.readiness.ReleasedOverrideChecks
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.ZoneId

/**
 * `inv.filing.*` of application.yaml: no default in code (fail closed on missing configuration, 12-factor). The
 * business zone is the jurisdiction's zone in which a filing instant becomes a calendar date (ISO 8601; coordinator
 * ruling round z, 2026-11-12). A missing, blank or invalid id fails the start.
 */
@ConfigurationProperties("inv.filing")
data class FilingProperties(
    val businessZone: String,
) {
    init {
        require(businessZone.isNotBlank()) { "inv.filing.business-zone must not be blank" }
    }
}

/** TPL-18 wiring of `confirmFiling` (INV-REQ-914, INV-AC-20947): the zone, the three adapters and the use case. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FilingProperties::class)
class FilingConfiguration {
    @Bean
    fun filingBusinessZone(properties: FilingProperties): ZoneId = ZoneId.of(properties.businessZone)

    @Bean
    fun filingDueDates(
        dsl: DSLContext,
        tenants: TenantContext,
    ): FilingDueDates = JooqFilingDueDates(dsl, tenants)

    @Bean
    fun latestChecks(readinessCheckReader: ReadinessCheckReader): LatestChecks =
        ReadinessLatestChecks(readinessCheckReader)

    @Bean
    fun gateModesAt(
        reader: PolicyReader,
        tenants: TenantContext,
    ): GateModesAt = PolicyGateModes(reader, tenants)

    @Bean
    @Suppress("LongParameterList")
    fun confirmFiling(
        replica: AuthorityReplica,
        dueDates: FilingDueDates,
        gateModes: GateModesAt,
        latestChecks: LatestChecks,
        releasedOverrides: ReleasedOverrideChecks,
        facts: FilingFactStore,
    ): ConfirmFiling = ConfirmFiling(replica, dueDates, gateModes, latestChecks, releasedOverrides, facts)
}
