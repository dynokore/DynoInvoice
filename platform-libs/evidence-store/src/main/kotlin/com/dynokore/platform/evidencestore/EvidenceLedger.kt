package com.dynokore.platform.evidencestore

import com.dynokore.platform.tenancykernel.TenantId

/**
 * The append-only, hash-chained evidence ledger (FR-9.15, FR-D.1, FR-D.2,
 * INV-REQ-2570): one chain per (tenant, entity, period). No `update` or
 * `delete` method exists anywhere on this port — CS-22's grant-and-trigger
 * guarantee (`EvidenceLedgerSchema`) is enforced by the database; this
 * interface additionally makes it structurally unreachable from application
 * code, the same pattern
 * [com.dynokore.platform.tenancykernel.LicenceCheck]/[com.dynokore.platform.tenancykernel.NonBlockingLicenceCheck]
 * uses for FR-G.12: a guarantee a caller cannot bypass because there is
 * nothing to call.
 *
 * Every method runs inside the caller's own
 * [com.dynokore.platform.tenancykernel.TransactionRunner] block (ARC-21: "no
 * repository call runs outside TransactionRunner") — there is no ambient
 * connection otherwise.
 */
interface EvidenceLedger {
    /**
     * Appends one record to the chain for (tenant, entity, period): its
     * [EvidenceRecord.sequence] is one past the chain's current tail (0 for
     * the first record), and its [EvidenceRecord.previousHash] is the tail's
     * [EvidenceRecord.contentHash] (`null` for the first record) — the
     * implementation computes both; the caller supplies only [content].
     */
    fun append(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
        type: EvidenceType,
        content: ByteArray,
        index: EvidenceIndex = EvidenceIndex.EMPTY,
    ): EvidenceRecord

    /** Indexed retrieval, scoped to entity and period (done-when): every record of the chain, in sequence order. */
    fun findByEntityAndPeriod(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
    ): List<EvidenceRecord>

    /**
     * Bulk export, scoped to entity and period (done-when, FR-9.15, FR-Q.17):
     * streamed from the database rather than materialised as one collection,
     * for a whole audit period at once.
     */
    fun bulkExport(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
    ): Sequence<EvidenceRecord>

    /** Recomputes the chain from the stored records and compares it against what is stored (FR-9.15 behaviour 4). */
    fun verifyChain(
        tenant: TenantId,
        entity: EntityRef,
        period: PeriodRef,
    ): ChainVerificationResult
}
