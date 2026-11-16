package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.idp.KeycloakGrantContacts
import com.dynokore.gst.access.adapters.persistence.JooqExpiryAlertLog
import com.dynokore.gst.access.adapters.persistence.JooqExpiryClaim
import com.dynokore.gst.access.adapters.persistence.JooqExpiryNoticeLog
import com.dynokore.gst.access.adapters.persistence.JooqExpiryScan
import com.dynokore.gst.access.adapters.persistence.JooqGrantRepository
import com.dynokore.gst.access.application.alert.AccessAlerts
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.ExpiryAlertLog
import com.dynokore.gst.access.application.grant.ExpiryAlerting
import com.dynokore.gst.access.application.grant.ExpiryNoticeLog
import com.dynokore.gst.access.application.grant.ExpiryNoticeMessages
import com.dynokore.gst.access.application.grant.ExpiryNoticeSettings
import com.dynokore.gst.access.application.grant.ExpiryNoticeTelemetry
import com.dynokore.gst.access.application.grant.ExpiryRecipients
import com.dynokore.gst.access.application.grant.ExpiryScanSource
import com.dynokore.gst.access.application.grant.GrantContacts
import com.dynokore.gst.access.application.grant.ScanGrantExpiryNotices
import com.dynokore.gst.access.domain.grant.ConsentCheck
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.micrometer.core.instrument.MeterRegistry
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * `access.grant-expiry-notice.*`: how often the grant expiry-notice scan runs (INV-REQ-893). One required property, an
 * ISO-8601 duration set in `application.yaml`; there is no default in code, and a missing, zero or negative interval
 * stops the binding (fail closed).
 */
@ConfigurationProperties(prefix = "access.grant-expiry-notice")
data class GrantExpiryNoticeProperties(
    val interval: Duration,
) {
    init {
        require(interval.isPositive) { "access.grant-expiry-notice.interval must be a positive ISO-8601 duration" }
    }
}

/** The metric and the log line of an expiry notice recipient that could not be resolved: the grant reference only. */
class GrantExpiryNoticeTelemetry(
    private val registry: MeterRegistry,
) : ExpiryNoticeTelemetry {
    override fun recipientUnresolved(grantReference: String) {
        registry.counter(METRIC).increment()
        log.warn("grant expiry notice recipient unresolved for grant {}", grantReference)
    }

    companion object {
        const val METRIC = "grant_expiry_notice_recipient_unresolved"
        private val log = LoggerFactory.getLogger(GrantExpiryNoticeTelemetry::class.java)
    }
}

/**
 * The scheduler of the grant expiry-notice scan (INV-REQ-893): claims the tenants that hold a time-limited grant under
 * the platform binding (ARC-21, ARC 4.3 step 1a), then binds each one and runs the scan in one transaction, so the
 * `MessageRequested` rows and the records of the scan commit together. A tenant whose scan fails does not stop the
 * others; its next run is the next interval.
 */
class GrantExpiryNoticeScheduler(
    private val claim: JooqExpiryClaim,
    private val scan: ScanGrantExpiryNotices,
    private val tenants: TenantContext,
    private val platform: PlatformTenantBinding,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    /** One run: the number of tenants whose scan failed. */
    fun runOnce(): Int {
        val claimed = platform.bindPlatform(ACTOR) { claim.claimTenants() }
        return claimed.count { tenant ->
            runCatching {
                tenants.bind(tenant, ACTOR) { tx.inTransaction { scan.scan(clock.instant()) } }
            }.onFailure { log.warn("grant expiry-notice scan of tenant {} failed: {}", tenant, it.message) }
                .isFailure
        }
    }

    companion object {
        /** The workload principal the scan runs as (ARC-21). */
        const val ACTOR = "svc:access-svc/job:grant-expiry-notice"
        private val log = LoggerFactory.getLogger(GrantExpiryNoticeScheduler::class.java)
    }
}

/** Runs [GrantExpiryNoticeScheduler.runOnce] every `access.grant-expiry-notice.interval`, after the first interval. */
class GrantExpiryNoticeLifecycle(
    private val scheduler: GrantExpiryNoticeScheduler,
    private val properties: GrantExpiryNoticeProperties,
) : SmartLifecycle {
    private var executor: ScheduledExecutorService? = null

    override fun start() {
        val pool =
            Executors.newSingleThreadScheduledExecutor { Thread(it, "grant-expiry-notice").apply { isDaemon = true } }
        val millis = properties.interval.toMillis()
        pool.scheduleWithFixedDelay({ runCatching { scheduler.runOnce() } }, millis, millis, TimeUnit.MILLISECONDS)
        executor = pool
    }

    override fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    override fun isRunning(): Boolean = executor != null
}

/** TPL-18, continued: the grant expiry-notice scan (INV-T-15799). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GrantExpiryNoticeProperties::class)
class GrantExpiryNoticeJob {
    /** The Keycloak reader of addresses; the secret is read through SecretStore at every token request. */
    @Bean
    @ConditionalOnMissingBean
    fun grantContacts(
        properties: DirectoryProperties,
        secrets: SecretStore,
    ): GrantContacts =
        KeycloakGrantContacts(
            properties.baseUri,
            properties.realm,
            properties.clientId,
        ) { secrets.read(SecretRef(properties.clientSecretRef)).reveal() }

    @Bean
    fun expiryScanSource(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
        consent: ConsentCheck,
        directory: EntityDirectory,
    ): ExpiryScanSource = JooqExpiryScan(dsl, JooqGrantRepository(dsl, tenants, clock, consent), directory)

    @Bean
    fun expiryNoticeLog(
        dsl: DSLContext,
        clock: Clock,
    ): ExpiryNoticeLog = JooqExpiryNoticeLog(dsl, clock)

    @Bean
    fun expiryAlertLog(
        dsl: DSLContext,
        clock: Clock,
    ): ExpiryAlertLog = JooqExpiryAlertLog(dsl, clock)

    @Bean
    fun expiryNoticeTelemetry(registry: MeterRegistry): ExpiryNoticeTelemetry = GrantExpiryNoticeTelemetry(registry)

    @Bean
    fun scanGrantExpiryNotices(
        source: ExpiryScanSource,
        log: ExpiryNoticeLog,
        messages: ExpiryNoticeMessages,
        contacts: GrantContacts,
        telemetry: ExpiryNoticeTelemetry,
        alertLog: ExpiryAlertLog,
        alerts: AccessAlerts,
    ) = ScanGrantExpiryNotices(
        source,
        log,
        messages,
        ExpiryRecipients(contacts, telemetry),
        ExpiryAlerting(alertLog, alerts),
        ExpiryNoticeSettings(ServiceDefaults.BUSINESS_ZONE),
    )

    @Bean
    fun expiryClaim(
        dsl: DSLContext,
        tx: TransactionRunner,
    ) = JooqExpiryClaim(dsl, tx)

    @Bean
    fun grantExpiryNoticeScheduler(
        claim: JooqExpiryClaim,
        scan: ScanGrantExpiryNotices,
        tenants: TenantContext,
        platform: PlatformTenantBinding,
        tx: TransactionRunner,
        clock: Clock,
    ) = GrantExpiryNoticeScheduler(claim, scan, tenants, platform, tx, clock)

    @Bean
    fun grantExpiryNoticeLifecycle(
        scheduler: GrantExpiryNoticeScheduler,
        properties: GrantExpiryNoticeProperties,
    ) = GrantExpiryNoticeLifecycle(scheduler, properties)
}
