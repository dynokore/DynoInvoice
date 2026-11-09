package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.InstallationAuditOutbox
import com.dynokore.gst.tenancy.adapters.events.InstallationStatusChangedOutbox
import com.dynokore.gst.tenancy.adapters.http.InstallerCallFilter
import com.dynokore.gst.tenancy.adapters.installation.ReplicaInstallationAuthority
import com.dynokore.gst.tenancy.adapters.licence.SignedLicenceFiles
import com.dynokore.gst.tenancy.adapters.persistence.InstallationActionRow
import com.dynokore.gst.tenancy.adapters.persistence.JooqInstallationStepStore
import com.dynokore.gst.tenancy.adapters.persistence.JooqLicenceStore
import com.dynokore.gst.tenancy.adapters.persistence.PlatformScope
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.INSTALLATION_ACTION
import com.dynokore.gst.tenancy.application.certification.ReleaseTrainChanged
import com.dynokore.gst.tenancy.application.installation.InstallationActionMetrics
import com.dynokore.gst.tenancy.application.installation.InstallationAudit
import com.dynokore.gst.tenancy.application.installation.InstallationAuthority
import com.dynokore.gst.tenancy.application.installation.InstallationFactRepository
import com.dynokore.gst.tenancy.application.installation.InstallationHealth
import com.dynokore.gst.tenancy.application.installation.InstallationStatus
import com.dynokore.gst.tenancy.application.installation.InstallationStatusPublisher
import com.dynokore.gst.tenancy.application.installation.InstallationStepStore
import com.dynokore.gst.tenancy.application.installation.LicenceFileVerifier
import com.dynokore.gst.tenancy.application.installation.LicenceStore
import com.dynokore.gst.tenancy.application.installation.LicenceTrustAnchors
import com.dynokore.gst.tenancy.application.installation.Licences
import com.dynokore.gst.tenancy.application.installation.RecordInstallationAction
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.crypto.BouncyCastleCms
import com.dynokore.platform.crypto.DetachedSignatureVerifier
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.persistence.PersistenceLayer
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.micrometer.core.instrument.MeterRegistry
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * `inv.installer.subject`: the subject of the installer Job's projected service-account token, the one workload
 * identity recordInstallationAction accepts (INV-Q-3826 A). Set by the chart; blank refuses every caller.
 */
@ConfigurationProperties(prefix = "inv.installer")
data class InstallerProperties(
    val subject: String = "",
)

/** The installation's own reference (INV-Q-14291 A), read from the environment once per call. */
fun interface InstallationRef {
    fun value(): String?
}

/** TPL-18: the installation-action use case (INV-T-3290), constructed here and nowhere else. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InstallerProperties::class)
@Suppress("TooManyFunctions") // one bean method per installation collaborator, constructed here and nowhere else
class InstallationUseCases {
    @Bean
    fun installationStepStore(
        dsl: DSLContext,
        persistence: PersistenceLayer,
    ): InstallationStepStore =
        JooqInstallationStepStore(dsl, persistence.ledger(INSTALLATION_ACTION, InstallationActionRow::class.java))

    @Bean
    fun installationAudit(writer: AuditWriter): InstallationAudit = InstallationAuditOutbox(writer)

    /** `vp-52.set` is decided at INSTALLATION scope under the platform tenant (INV-Q-10027). */
    @Bean
    @ConditionalOnMissingBean(InstallationAuthority::class)
    fun installationAuthority(replica: ObjectProvider<AuthorityReplica>): InstallationAuthority =
        ReplicaInstallationAuthority(replica.getIfAvailable())

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
        markRequested: ReleaseTrainChanged,
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
            // appends CertificationMarkRequested in this transaction; the marks run on delivery (INV-Q-18156)
            markRequested,
        )

    /** The form is the platform property `gst.form`; the SaaS form refuses every platform election. */
    @Bean
    @Suppress("LongParameterList")
    fun installationStatus(
        tenants: TenantContext,
        tx: TransactionRunner,
        facts: InstallationFactRepository,
        clock: ObjectProvider<Clock>,
        environment: Environment,
    ): InstallationStatus =
        InstallationStatus(
            tenants as PlatformTenantBinding,
            tx,
            facts,
            { clock.getIfAvailable(Clock::systemUTC).instant() },
            { environment.getProperty("gst.form", "SAAS").trim().uppercase() != "PRIVATE" },
            "tenancy-svc",
        )

    /**
     * The installation's own reference: the property `gst.installation.ref`, 1 to 64 characters of `[A-Za-z0-9._-]`
     * (INV-Q-14291 A); null when absent, blank or of another form.
     */
    @Bean
    fun installationRef(environment: Environment): InstallationRef =
        InstallationRef {
            environment.getProperty("gst.installation.ref")?.takeIf { REF.matches(it) }
        }

    /** The DER certificates a licence signer must equal: `gst.licence.trust-anchors`, base64, comma-separated. */
    @Bean
    @ConditionalOnMissingBean(LicenceTrustAnchors::class)
    fun licenceTrustAnchors(environment: Environment): LicenceTrustAnchors {
        val der =
            environment
                .getProperty("gst.licence.trust-anchors", "")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map {
                    java.util.Base64
                        .getDecoder()
                        .decode(it)
                }
        return if (der.isEmpty()) LicenceTrustAnchors.NONE else LicenceTrustAnchors { der }
    }

    @Bean
    fun installationStatusPublisher(
        outbox: OutboxWriter,
        mapper: ObjectMapper,
        ref: InstallationRef,
    ): InstallationStatusPublisher = InstallationStatusChangedOutbox(outbox, mapper) { ref.value() }

    @Bean
    @Suppress("LongParameterList")
    fun installationHealth(
        tenants: TenantContext,
        tx: TransactionRunner,
        facts: InstallationFactRepository,
        publisher: InstallationStatusPublisher,
        trainChanged: ReleaseTrainChanged,
        clock: ObjectProvider<Clock>,
    ): InstallationHealth =
        InstallationHealth(
            tenants as PlatformTenantBinding,
            tx,
            facts,
            publisher,
            trainChanged,
            { clock.getIfAvailable(Clock::systemUTC).instant() },
            "tenancy-svc",
        )

    @Bean
    fun licenceStore(
        dsl: DSLContext,
        tenants: TenantContext,
        tx: TransactionRunner,
    ): LicenceStore = JooqLicenceStore(dsl, PlatformScope(tenants as PlatformTenantBinding, tx, "tenancy-svc"))

    /** The detached CMS verifier of the platform crypto module (STK-13). */
    @Bean
    @ConditionalOnMissingBean(DetachedSignatureVerifier::class)
    fun detachedSignatureVerifier(): DetachedSignatureVerifier = BouncyCastleCms()

    @Bean
    fun licenceFileVerifier(
        verifier: DetachedSignatureVerifier,
        anchors: LicenceTrustAnchors,
        ref: InstallationRef,
    ): LicenceFileVerifier = SignedLicenceFiles(verifier, anchors) { ref.value() }

    @Bean
    @Suppress("LongParameterList")
    fun licences(
        tenants: TenantContext,
        tx: TransactionRunner,
        verifier: LicenceFileVerifier,
        store: LicenceStore,
        health: InstallationHealth,
        clock: ObjectProvider<Clock>,
    ): Licences =
        Licences(
            tenants as PlatformTenantBinding,
            tx,
            verifier,
            store,
            health,
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
        val REF = Regex("^[A-Za-z0-9._-]{1,64}$")

        /** After the workload-token filter of service-runtime (-90), before everything that reads the body. */
        const val INSTALLER_FILTER_ORDER = -80
        const val DIFFERING_REPEAT_METER = "tenancy.installation_action.differing_repeat"
    }
}
