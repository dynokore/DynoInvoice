package com.dynokore.platform.evidencestore

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant

/**
 * One append-only row of the evidence ledger (FR-D.2, INV-REQ-2570): identified
 * by its position in the per-(tenant, entity, period) hash chain ([sequence],
 * 0 for the first record of a chain) — never by an update. There is no
 * `update`/`delete` anywhere on [EvidenceLedger]: nothing to call, so nothing
 * to bypass (the same structural guarantee
 * [com.dynokore.platform.tenancykernel.NonBlockingLicenceCheck] gives FR-G.12).
 */
data class EvidenceRecord(
    val tenantId: TenantId,
    val entityRef: EntityRef,
    val periodRef: PeriodRef,
    val evidenceType: EvidenceType,
    val sequence: Long,
    val content: ByteArray,
    val contentHash: ContentHash,
    val previousHash: ContentHash?,
    val recordedAt: Instant,
    val index: EvidenceIndex = EvidenceIndex.EMPTY,
) {
    // A data class with a ByteArray property gets no meaningful generated equals/hashCode
    // (reference equality on `content`); overridden explicitly since EvidenceRecord is
    // compared directly in this module's own tests (property tests included, STK-38).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EvidenceRecord) return false
        return tenantId == other.tenantId &&
            entityRef == other.entityRef &&
            periodRef == other.periodRef &&
            evidenceType == other.evidenceType &&
            sequence == other.sequence &&
            content.contentEquals(other.content) &&
            contentHash == other.contentHash &&
            previousHash == other.previousHash &&
            recordedAt == other.recordedAt &&
            index == other.index
    }

    override fun hashCode(): Int {
        var result = tenantId.hashCode()
        result = 31 * result + entityRef.hashCode()
        result = 31 * result + periodRef.hashCode()
        result = 31 * result + evidenceType.hashCode()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + content.contentHashCode()
        result = 31 * result + contentHash.hashCode()
        result = 31 * result + (previousHash?.hashCode() ?: 0)
        result = 31 * result + recordedAt.hashCode()
        result = 31 * result + index.hashCode()
        return result
    }
}
