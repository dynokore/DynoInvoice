package com.dynokore.platform.serviceruntime.diagnostics

import com.dynokore.platform.serviceruntime.health.DegradedStatus
import com.dynokore.platform.serviceruntime.health.DomainDependencyHealthIndicator
import com.dynokore.platform.serviceruntime.health.OutboxDepthSource
import com.dynokore.platform.serviceruntime.health.QueueDepthSource
import com.dynokore.platform.serviceruntime.health.VaultHealthIndicator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.health.actuate.endpoint.StatusAggregator
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.info.BuildProperties

/**
 * The uniform diagnostic endpoint (FR-AE.82): "each service exposes the same
 * diagnostic endpoint: version, health, recent error codes, queue and outbox
 * lag" — collected by the support bundle (INV-REQ-1106) and read directly by
 * SaaS operations (INV-REQ-36), the same response either way (FR-AE.82
 * behaviour #1-3). [queueDepthSource]/[outboxDepthSource] are absent until
 * `outbox-and-queue` ships its own code; the report then carries `null` for
 * that lag rather than failing the endpoint.
 *
 * `health` aggregates every FR-AH.17 contributor this service has, not the
 * vault alone: [vaultHealthIndicator] (the vault third every service needs to
 * start live) together with every [DomainDependencyHealthIndicator] the
 * service's own bootstrap has registered, one per synchronous edge it calls.
 * A missing domain dependency must surface here as `DEGRADED` — the whole
 * point of FR-AH.17's own example, `ingestion-svc` reporting `503` while
 * `canonical-svc` is down — so the order mirrors TPL-16's
 * `management.endpoint.health.status.order` (`DOWN` outranks `DEGRADED`
 * outranks `UP`; the vault down is never merely degraded, §36.7).
 */
@Endpoint(id = "diagnostics")
class DiagnosticsEndpoint(
    private val buildProperties: ObjectProvider<BuildProperties>,
    private val vaultHealthIndicator: VaultHealthIndicator,
    private val domainDependencyHealthIndicators: List<DomainDependencyHealthIndicator>,
    private val recorder: RecentErrorCodesRecorder,
    private val queueDepthSource: ObjectProvider<QueueDepthSource>,
    private val outboxDepthSource: ObjectProvider<OutboxDepthSource>,
) {
    private val statusAggregator: StatusAggregator = StatusAggregator.of(Status.DOWN, DegradedStatus.DEGRADED, Status.UP)

    @ReadOperation
    fun diagnostics(): DiagnosticsReport = DiagnosticsReport(
        version = buildProperties.getIfAvailable()?.version ?: "unknown",
        health = statusAggregator.getAggregateStatus(aggregatedHealthStatuses()).code,
        recentErrorCodes = recorder.snapshot(),
        queueDepth = queueDepthSource.getIfAvailable()?.depth(),
        outboxDepth = outboxDepthSource.getIfAvailable()?.depth(),
    )

    private fun aggregatedHealthStatuses(): Set<Status> = buildSet {
        add(vaultHealthIndicator.health().status)
        domainDependencyHealthIndicators.forEach { add(it.health().status) }
    }
}
