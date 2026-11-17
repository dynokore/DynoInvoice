package com.dynokore.gst.reconciliation.application.returns

import com.dynokore.gst.reconciliation.domain.returns.RecordedReturn
import java.math.BigDecimal
import java.time.Instant

/**
 * A stored return version (INV-REQ-653). [uploadedBy] is the acting user ID of the uploading principal, never a role
 * and never the free-text [preparer], which is display data only.
 */
data class StoredReturn(
    val recorded: RecordedReturn,
    val boxes: Map<String, BigDecimal>,
    val filingReference: String?,
    val preparer: String?,
    val uploadedBy: String,
    val recordedAt: Instant,
)

/** The return versions of one (tenant, entity, period). The caller owns the transaction. */
interface ReturnRecords {
    fun recorded(
        tenant: String,
        entityRef: String,
        periodRef: String,
    ): List<StoredReturn>

    /** True when this call inserted the version; false when it already existed (a lost concurrent-upload race). */
    fun insertIfAbsent(
        tenant: String,
        entityRef: String,
        periodRef: String,
        stored: StoredReturn,
    ): Boolean
}

/** Hands the `GstReturnRecorded` event to the transactional outbox; the reference is `stored.filingReference`. */
fun interface GstReturnRecordedPublisher {
    fun publish(
        tenant: String,
        entityRef: String,
        periodRef: String,
        stored: StoredReturn,
    )
}

/** Queues a reconciliation run against a return version. */
fun interface RunTrigger {
    fun enqueue(
        tenant: String,
        entityRef: String,
        periodRef: String,
        returnVersion: Int,
    )
}
