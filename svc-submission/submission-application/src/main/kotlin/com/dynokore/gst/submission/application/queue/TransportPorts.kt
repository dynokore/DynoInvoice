package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.transitions.RejectionReason
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The call to transport-svc for one attempt; [idempotencyKey] is the correlation ID of the attempt (INV-Q-14328 A). */
class TransportRequest(
    val tenant: TenantId,
    val docUuid: String,
    val version: Int,
    val entityRef: String,
    val payload: ByteArray?,
    val idempotencyKey: UUID,
)

/** The outcome of one call (INV-Q-14322 A); [responseBytes] is stored as the response of the attempt. */
sealed interface TransportResult {
    val responseBytes: ByteArray

    class Accepted(
        val acknowledgementId: String,
        val receivedAt: Instant,
        override val responseBytes: ByteArray,
    ) : TransportResult

    class Rejected(
        val reasons: List<RejectionReason>,
        override val responseBytes: ByteArray,
    ) : TransportResult

    class TransientFailure(
        override val responseBytes: ByteArray,
    ) : TransportResult
}

/** The port to transport-svc; its HTTP adapter is a later slice. */
fun interface TransportClient {
    fun submit(request: TransportRequest): TransportResult
}

/** The `retry_boundary` of the AP connection of an entity (VP-48), null when none is set (INV-Q-14316 A). */
fun interface RetryBoundarySource {
    fun boundary(
        tenant: TenantId,
        entityRef: String,
    ): Duration?
}

/** A draw in [0, 1) for the jitter of the backoff (INV-Q-14328 A). */
fun interface JitterSource {
    fun draw(): Double
}

/** The tenants that hold a due `RETRY` row (INV-Q-14325 A). */
fun interface DueTenantSource {
    fun tenants(now: Instant): List<TenantId>
}
