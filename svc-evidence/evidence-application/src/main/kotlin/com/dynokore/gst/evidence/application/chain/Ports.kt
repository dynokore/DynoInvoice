package com.dynokore.gst.evidence.application.chain

import com.dynokore.gst.evidence.domain.chain.ChainKey
import com.dynokore.gst.evidence.domain.chain.DocumentLocator
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.util.UUID

/** A document-bound record that arrived before its locator, as stored in `pending_record`. */
data class PendingRecord(
    val eventId: String,
    val kind: RecordKind,
    val docUuid: UUID,
    val version: Int,
    val eventJson: String,
    val arrivedAt: Instant,
)

/** The row of `applied_record` that marks an event as appended to a chain. */
data class AppliedRecord(
    val eventId: String,
    val kind: RecordKind,
    val chain: ChainKey,
    val sequence: Long,
    val appliedAt: Instant,
)

/** `document_locator`, append-only. Every method runs in the caller's tenant transaction. */
interface LocatorStore {
    fun find(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ): DocumentLocator?

    /** Stores [locator] and returns true; false and nothing stored when (docUuid, version) or [eventId] has a row. */
    fun insert(
        tenant: TenantId,
        eventId: String,
        locator: DocumentLocator,
        recordedAt: Instant,
    ): Boolean

    /**
     * Serialises the writers of one document version until the transaction ends, so a pending record and its locator
     * cannot each miss the other's uncommitted row.
     */
    fun lock(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    )
}

/** `pending_record`, append-only. */
interface PendingStore {
    /** Stores [record]; a repeat of (tenant, event id) stores nothing and returns false. */
    fun insert(
        tenant: TenantId,
        record: PendingRecord,
    ): Boolean

    /**
     * The pending records of the document version with no `applied_record` row for the same (tenant, event id), in the
     * order of `arrived_at` then event id. Only [RecordAppender.drainPending] reads them.
     */
    fun unapplied(
        tenant: TenantId,
        docUuid: UUID,
        version: Int,
    ): List<PendingRecord>
}

/** `applied_record`, append-only. */
interface AppliedStore {
    fun isApplied(
        tenant: TenantId,
        eventId: String,
    ): Boolean

    fun insert(
        tenant: TenantId,
        record: AppliedRecord,
    )
}
