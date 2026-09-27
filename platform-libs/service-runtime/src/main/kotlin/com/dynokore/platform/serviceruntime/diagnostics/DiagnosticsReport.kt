package com.dynokore.platform.serviceruntime.diagnostics

/**
 * The uniform diagnostic endpoint's payload (FR-AE.82): "version, health, recent
 * error codes, queue and outbox lag" — the same shape every service exposes, so
 * the support bundle (INV-REQ-1106) and SaaS operations (INV-REQ-36) share one
 * collection tool (FR-AE.82 behaviour #2, #3). [queueDepth] and [outboxDepth] are
 * `null` when this service has not bound a
 * [QueueDepthSource][com.dynokore.platform.serviceruntime.health.QueueDepthSource]
 * or
 * [OutboxDepthSource][com.dynokore.platform.serviceruntime.health.OutboxDepthSource]
 * (`outbox-and-queue` has no code yet) rather than failing the endpoint.
 */
data class DiagnosticsReport(
    val version: String,
    val health: String,
    val recentErrorCodes: List<ErrorCodeCount>,
    val queueDepth: Long?,
    val outboxDepth: Long?,
)

/** One recently-raised error code's tally, for [DiagnosticsReport.recentErrorCodes]. Identifiers only (CS-34). */
data class ErrorCodeCount(
    val code: String,
    val count: Long,
    val lastRaisedAt: String,
)
