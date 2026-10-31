package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.InstallationAuditOutbox
import com.dynokore.gst.tenancy.adapters.http.InstallerCallFilter
import com.dynokore.gst.tenancy.adapters.persistence.InstallationActionRow
import com.dynokore.gst.tenancy.adapters.persistence.JooqInstallationStepStore
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.INSTALLATION_ACTION
import com.dynokore.gst.tenancy.application.installation.InstallationActionMetrics
import com.dynokore.gst.tenancy.application.installation.InstallationAudit
import com.dynokore.gst.tenancy.application.installation.InstallationFactRepository
import com.dynokore.gst.tenancy.application.installation.InstallationStepStore
import com.dynokore.gst.tenancy.application.installation.RecordInstallationAction
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.persistence.PersistenceLayer
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.micrometer.core.instrument.MeterRegistry
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * `inv.installer.subject`: the subject of the installer Job's projected service-account token, the one workload
 * identity recordInstallationAction accepts (INV-Q-3826 A). Set by the chart; blank refuses every caller.
 */
@ConfigurationProperties(prefix = "inv.installer")
data class InstallerProperties(
    val subject: String = "",
)

/** TPL-18: the installation-action use case (INV-T-3290), constructed here and nowhere else. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InstallerProperties::class)
class InstallationUseCases {
    @Bean
    fun installationStepStore(
        dsl: DSLContext,
        persistence: PersistenceLayer,
    ): InstallationStepStore =
        JooqInstallationStepStore(dsl, persistence.ledger(INSTALLATION_ACTION, InstallationActionRow::class.java))

    @Bean
    fun installationAudit(writer: AuditWriter): InstallationAudit = InstallationAuditOutbox(writer)

    @Bean
    fun installationActionMetrics(registry: MeterRegistry): InstallationActionMetrics {
        val counter = registry.counter(DIFFERING_REPEAT_METER)
        return InstallationActionMetrics { counter.increment() }
    }

    @Bean
    @Suppress("LongParameterList")
    fun recordInstallationAction(
        tenants: TenantContext,
        tx: TransactionRunner,
        steps: InstallationStepStore,
        facts: InstallationFactRepository,
        audit: InstallationAudit,
        metrics: InstallationActionMetrics,
        clock: ObjectProvider<Clock>,
    ): RecordInstallationAction =
        RecordInstallationAction(
            tenants as PlatformTenantBinding,
            tx,
            steps,
            facts,
            audit,
            metrics,
            { clock.getIfAvailable(Clock::systemUTC).instant() },
            "tenancy-svc",
        )

    /** The one workload subject allowed to call recordInstallationAction; blank refuses every caller (INV-Q-3826 A). */
    @Bean
    fun installerCallFilter(
        verifier: WorkloadTokenVerifier,
        installer: InstallerProperties,
    ): FilterRegistrationBean<InstallerCallFilter> =
        FilterRegistrationBean(InstallerCallFilter(verifier, installer.subject)).apply {
            order = INSTALLER_FILTER_ORDER
            urlPatterns = listOf(InstallerCallFilter.PATH, InstallerCallFilter.PATH + "/*")
        }

    private companion object {
        /** After the workload-token filter of service-runtime (-90), before everything that reads the body. */
        const val INSTALLER_FILTER_ORDER = -80
        const val DIFFERING_REPEAT_METER = "tenancy.installation_action.differing_repeat"
    }
}
