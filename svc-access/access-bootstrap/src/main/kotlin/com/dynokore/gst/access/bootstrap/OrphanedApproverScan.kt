package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.application.alert.OrphanedApprovers
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.SmartLifecycle
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * `access.orphaned-approver.*`: how often the ACC-001 scan runs (INV-REQ-895 behaviour 4). One required property; the
 * value is read from `application.yaml`, there is no default in code, and a missing, zero or negative interval stops
 * the binding (fail closed).
 */
@ConfigurationProperties(prefix = "access.orphaned-approver")
data class OrphanedApproverProperties(
    val interval: Duration,
) {
    init {
        require(interval.isPositive) { "access.orphaned-approver.interval must be a positive ISO-8601 duration" }
    }
}

/**
 * The scheduled scan of ACC-001 (INV-REQ-895 behaviour 4): claims the tenants that hold grants, binds each one
 * (ARC-21, ARC 4.3 step 1a) and settles every entity of the tenant in one transaction. It also catches a grant whose
 * validity window ended without any write. A tenant whose evaluation fails is counted and does not stop the next.
 */
class OrphanedApproverScan(
    private val claim: () -> List<TenantId>,
    private val approvers: OrphanedApprovers,
    private val directory: EntityDirectory,
    private val tenants: TenantContext,
    private val platform: PlatformTenantBinding,
    private val tx: TransactionRunner,
) {
    /** One run: the number of tenants whose evaluation failed. */
    fun runOnce(): Int {
        val claimed = platform.bindPlatform(ACTOR) { claim() }
        return claimed.count { tenant ->
            runCatching {
                tenants.bind(tenant, ACTOR) {
                    tx.inTransaction {
                        approvers.settle(directory.entitiesCoveredBy(Scope.Tenant(TenantRef(tenant.toString()))))
                    }
                }
            }.onFailure { log.warn("orphaned-approver scan of tenant {} failed: {}", tenant, it.message) }
                .isFailure
        }
    }

    companion object {
        /** The workload principal the scan runs as (ARC-21). */
        const val ACTOR = "svc:access-svc/job:orphaned-approver-scan"
        private val log = LoggerFactory.getLogger(OrphanedApproverScan::class.java)
    }
}

/** Runs [OrphanedApproverScan.runOnce] every `access.orphaned-approver.interval`, after the first interval. */
class OrphanedApproverScanLifecycle(
    private val scan: OrphanedApproverScan,
    private val properties: OrphanedApproverProperties,
) : SmartLifecycle {
    private var executor: ScheduledExecutorService? = null

    override fun start() {
        val pool =
            Executors.newSingleThreadScheduledExecutor {
                Thread(
                    it,
                    "orphaned-approver-scan",
                ).apply { isDaemon = true }
            }
        val millis = properties.interval.toMillis()
        pool.scheduleWithFixedDelay({ runCatching { scan.runOnce() } }, millis, millis, TimeUnit.MILLISECONDS)
        executor = pool
    }

    override fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    override fun isRunning(): Boolean = executor != null
}
