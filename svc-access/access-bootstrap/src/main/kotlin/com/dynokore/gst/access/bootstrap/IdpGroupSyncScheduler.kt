package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.persistence.JooqIdpSyncClaim
import com.dynokore.gst.access.application.idp.IdpSyncClaim
import com.dynokore.gst.access.application.idp.SyncIdpGroupsUseCase
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The scheduler of the directory-group synchronisation (INV-REQ-892 behaviour 3, INV-Q-8665 A, INV-Q-8843 A): finds the
 * tenants with an ACTIVE mapping through the claim port, binds each one (ARC-21, ARC 4.3 step 1a) and runs the use case
 * for it, one tenant after another. The claim runs under the platform binding; a tenant whose run fails does not stop
 * the others, and its next run is the next interval.
 */
class IdpGroupSyncScheduler(
    private val claim: IdpSyncClaim,
    private val sync: SyncIdpGroupsUseCase,
    private val tenants: TenantContext,
    private val platform: PlatformTenantBinding,
) {
    /** One run: the number of tenants whose synchronisation failed. */
    fun runOnce(): Int {
        val claimed = platform.bindPlatform(ACTOR) { claim.claimTenants() }
        return claimed.count { tenant ->
            runCatching {
                tenants.bind(tenant, ACTOR) { sync.run(ACTOR, TenantRef(tenants.current().toString())) }
            }.onFailure { log.warn("directory-group synchronisation of tenant {} failed: {}", tenant, it.message) }
                .isFailure
        }
    }

    companion object {
        /** The workload principal the synchronisation runs as (ARC-21). */
        const val ACTOR = "svc:access-svc/job:idp-group-sync"
        private val log = LoggerFactory.getLogger(IdpGroupSyncScheduler::class.java)
    }
}

/** Runs [IdpGroupSyncScheduler.runOnce] every `access.idp-group-sync.interval`, after the first interval. */
class IdpGroupSyncLifecycle(
    private val scheduler: IdpGroupSyncScheduler,
    private val properties: IdpGroupSyncProperties,
) : SmartLifecycle {
    private var executor: ScheduledExecutorService? = null

    override fun start() {
        val pool = Executors.newSingleThreadScheduledExecutor { Thread(it, "idp-group-sync").apply { isDaemon = true } }
        val millis = properties.interval.toMillis()
        pool.scheduleWithFixedDelay(
            { runCatching { scheduler.runOnce() } },
            millis,
            millis,
            TimeUnit.MILLISECONDS,
        )
        executor = pool
    }

    override fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    override fun isRunning(): Boolean = executor != null
}

/** TPL-18, continued: the synchronisation job (INV-T-4938). */
@Configuration(proxyBeanMethods = false)
class IdpGroupSyncJob {
    @Bean
    fun idpSyncClaim(
        dsl: DSLContext,
        tx: TransactionRunner,
    ): IdpSyncClaim = JooqIdpSyncClaim(dsl, tx)

    @Bean
    fun idpGroupSyncScheduler(
        claim: IdpSyncClaim,
        sync: SyncIdpGroupsUseCase,
        tenants: TenantContext,
        platform: PlatformTenantBinding,
    ) = IdpGroupSyncScheduler(claim, sync, tenants, platform)

    @Bean
    fun idpGroupSyncLifecycle(
        scheduler: IdpGroupSyncScheduler,
        properties: IdpGroupSyncProperties,
    ) = IdpGroupSyncLifecycle(scheduler, properties)
}
