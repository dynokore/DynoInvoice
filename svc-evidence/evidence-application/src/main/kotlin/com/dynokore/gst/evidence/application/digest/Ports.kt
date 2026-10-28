package com.dynokore.gst.evidence.application.digest

import com.dynokore.gst.evidence.domain.digest.ChainTail
import com.dynokore.gst.evidence.domain.digest.DigestChainType
import com.dynokore.platform.evidencestore.RetentionPeriod
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant

/** One `period_digest` row with its three `period_digest_chain` rows (INV-Q-7251 A (2)). */
data class PeriodDigestEntry(
    val entityRef: String,
    val period: String,
    val digestSequence: Int,
    val returnVersion: Int,
    val filedAt: Instant,
    val recordedAt: Instant,
    val eventId: String,
    val chains: List<ChainTail>,
    val digest: String,
)

/** `period_digest` and `period_digest_chain`, append-only. Every method runs in the caller's tenant transaction. */
interface PeriodDigestStore {
    /** Serialises the writers of one (tenant, entity, period) until the transaction ends. */
    fun lock(
        tenant: TenantId,
        entityRef: String,
        period: String,
    )

    /** The digest sequence the next digest of (tenant, entity, period) takes: 1 for the first. */
    fun nextSequence(
        tenant: TenantId,
        entityRef: String,
        period: String,
    ): Int

    fun insert(
        tenant: TenantId,
        entry: PeriodDigestEntry,
    )
}

/** The length and last `contentHash` of a document-bound chain of (tenant, entity, period); empty when it has none. */
interface ChainTails {
    fun tail(
        tenant: TenantId,
        entityRef: String,
        period: String,
        type: DigestChainType,
    ): ChainTail
}

/** The write-once object store of the digest copies (key `period-digests/{tenant}/{entity}/{period}/{seq}.json`). */
interface DigestObjects {
    /**
     * Puts the JSON copy of [entry] under its key with [retainUntil]. A key that already holds the identical bytes is
     * kept as it is; a key that holds other bytes raises [DigestObjectConflict].
     */
    fun putOrVerify(
        tenant: TenantId,
        entry: PeriodDigestEntry,
        retainUntil: Instant,
    )
}

/** The installation's longest VP-43 evidence retention (the value `getMaxEvidenceRetention` serves, INV-REQ-3767). */
fun interface MaxRetention {
    fun longest(): RetentionPeriod
}

/** An object already sits under the key of a digest with other bytes: a poison record. */
class DigestObjectConflict(
    val key: String,
) : RuntimeException("the object $key holds other bytes than the digest")
