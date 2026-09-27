package com.dynokore.platform.serviceruntime.diagnostics

import com.dynokore.platform.serviceruntime.health.OutboxDepthSource
import com.dynokore.platform.serviceruntime.health.QueueDepthSource
import com.dynokore.platform.serviceruntime.health.VaultHealthIndicator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.info.BuildProperties

/**
 * The uniform diagnostic endpoint (FR-AE.82): "each service exposes the same
 * diagnostic endpoint: version, health, recent error codes, queue and outbox
 * lag" — collected by the support bundle (INV-REQ-1106) and read directly by
 * SaaS operations (INV-REQ-36), the same response either way (FR-AE.82
 * behaviour #1-3). [queueDepthSource]/[outboxDepthSource] are absent until
 * `outbox-and-queue` ships its own code; the report then carries `null` for
 * that lag rather than failing the endpoint.
 */
@Endpoint(id = "diagnostics")
class DiagnosticsEndpoint(
    private val buildProperties: ObjectProvider<BuildProperties>,
    private val vaultHealthIndicator: VaultHealthIndicator,
    private val recorder: RecentErrorCodesRecorder,
    private val queueDepthSource: ObjectProvider<QueueDepthSource>,
    private val outboxDepthSource: ObjectProvider<OutboxDepthSource>,
) {
    @ReadOperation
    fun diagnostics(): DiagnosticsReport = DiagnosticsReport(
        version = buildProperties.getIfAvailable()?.version ?: "unknown",
        health = vaultHealthIndicator.health().status.code,
        recentErrorCodes = recorder.snapshot(),
        queueDepth = queueDepthSource.getIfAvailable()?.depth(),
        outboxDepth = outboxDepthSource.getIfAvailable()?.depth(),
    )
}
