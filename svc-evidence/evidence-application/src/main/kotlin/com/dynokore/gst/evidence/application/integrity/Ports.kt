package com.dynokore.gst.evidence.application.integrity

import com.dynokore.gst.evidence.application.digest.PeriodDigestEntry
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.gst.evidence.domain.integrity.FailureKind
import com.dynokore.gst.evidence.domain.integrity.RunCause
import com.dynokore.gst.evidence.domain.integrity.RunResult
import com.dynokore.platform.evidencestore.ChainVerificationResult
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant

/** The length of a chain and the `recordedAt` of its first and last record; both times are null for an empty chain. */
data class ChainFacts(
    val length: Long,
    val firstAt: Instant?,
    val lastAt: Instant?,
)

/** The chains of an entity as the tables hold them. Every method runs in the caller's tenant transaction. */
interface ChainReads {
    /** The periods of (tenant, entity) that have a chain known to `applied_record` or a period digest. */
    fun periods(
        tenant: TenantId,
        entityRef: String,
    ): List<String>

    /** The chains of (tenant, entity, period) known to `applied_record`, one entry per table. */
    fun kinds(
        tenant: TenantId,
        entityRef: String,
        period: String,
    ): List<RecordKind>

    fun facts(
        tenant: TenantId,
        entityRef: String,
        period: String,
        kind: RecordKind,
    ): ChainFacts

    /** The `contentHash` of the record at [sequence], or null when the chain has none there. */
    fun hashAt(
        tenant: TenantId,
        entityRef: String,
        period: String,
        kind: RecordKind,
        sequence: Long,
    ): String?
}

/** The hash-chain check of one chain, as `EvidenceLedger.verifyChain` answers it. */
fun interface ChainChecks {
    fun verify(
        tenant: TenantId,
        entityRef: String,
        period: String,
        kind: RecordKind,
    ): ChainVerificationResult
}

/** The period digests of (tenant, entity, period), with their chain rows as stored, by digest sequence. */
fun interface DigestReads {
    fun digests(
        tenant: TenantId,
        entityRef: String,
        period: String,
    ): List<PeriodDigestEntry>
}

/** The write-once object copies of the period digests. */
fun interface DigestCopies {
    /**
     * True when the object under the key of [entry] holds the bytes the stored rows give; false when it is absent or
     * holds other bytes. Any other failure to read it propagates.
     */
    fun matches(
        tenant: TenantId,
        entry: PeriodDigestEntry,
    ): Boolean
}

/** What a verification reads: the chains, the period digests and the object copies of the digests. */
class VerificationSources(
    val chains: ChainReads,
    val digests: DigestReads,
    val copies: DigestCopies,
)

/** One `integrity_run` row. */
data class IntegrityRunRow(
    val entityRef: String,
    val period: String,
    val runId: String,
    val runAt: Instant,
    val result: RunResult,
    val cause: RunCause,
    val chainLength: Long,
    val periodDigest: String?,
)

/** One `integrity_failure` row (INV-Q-7253 A (1)); [failingSequence] is null for a digest or object copy cause. */
data class IntegrityFailureRow(
    val entityRef: String,
    val period: String,
    val kind: RecordKind,
    val failure: FailureKind,
    val failingSequence: Long?,
    val rangeFrom: Instant,
    val rangeTo: Instant,
    val runAt: Instant,
    val runId: String,
)

/** `integrity_run` and `integrity_failure`, append-only. Every method runs in the caller's tenant transaction. */
interface IntegrityStore {
    /** Serialises the verifiers of one (tenant, entity, period) until the transaction ends. */
    fun lock(
        tenant: TenantId,
        entityRef: String,
        period: String,
    )

    /** True when a row of the same chain key, failure kind and failing sequence exists. */
    fun failureExists(
        tenant: TenantId,
        row: IntegrityFailureRow,
    ): Boolean

    fun insertFailure(
        tenant: TenantId,
        row: IntegrityFailureRow,
    )

    fun insertRun(
        tenant: TenantId,
        row: IntegrityRunRow,
    )

    /** The run of (tenant, entity, period) with the latest run time, or null when there is none. */
    fun latestRun(
        tenant: TenantId,
        entityRef: String,
        period: String,
    ): IntegrityRunRow?
}
