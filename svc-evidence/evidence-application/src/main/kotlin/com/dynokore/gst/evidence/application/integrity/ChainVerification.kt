package com.dynokore.gst.evidence.application.integrity

import com.dynokore.gst.evidence.application.digest.PeriodDigestEntry
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.gst.evidence.domain.digest.DigestChainType
import com.dynokore.gst.evidence.domain.digest.periodDigest
import com.dynokore.gst.evidence.domain.integrity.FailureKind
import com.dynokore.gst.evidence.domain.integrity.RunCause
import com.dynokore.gst.evidence.domain.integrity.RunResult
import com.dynokore.gst.evidence.domain.integrity.isAuditPeriod
import com.dynokore.gst.evidence.domain.integrity.kind
import com.dynokore.platform.evidencestore.ChainVerificationResult
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant

/** One finding of a verification (INV-Q-7253 A (1)); [failingSequence] is null for a digest or object copy cause. */
data class ChainFailure(
    val kind: RecordKind,
    val failure: FailureKind,
    val failingSequence: Long?,
    val rangeFrom: Instant,
    val rangeTo: Instant,
)

/** What one verification of an (entity, period) found; [failures] is empty unless [result] is FAILED by VERIFICATION. */
data class VerificationOutcome(
    val result: RunResult,
    val cause: RunCause,
    val chainLength: Long,
    val periodDigest: String?,
    val failures: List<ChainFailure>,
)

/**
 * The verification of one (entity, period) (INV-Q-7251 A (4), INV-Q-7253 A (1), INV-Q-12984 to INV-Q-12988 A). It
 * checks every chain of the period known to the sources and every `period_digest` row against the chains and the
 * object store copy, and returns the outcome; it writes nothing. It fails closed: a chain or copy that cannot be read
 * gives FAILED with cause UNREADABLE, chain length 0 and no failure.
 */
class ChainVerification(
    private val checks: ChainChecks,
    sources: VerificationSources,
) {
    private val chains = sources.chains
    private val digests = sources.digests
    private val copies = sources.copies

    @Suppress("TooGenericExceptionCaught") // any failure to read is UNREADABLE (INV-Q-12988 A)
    fun verifyPeriod(
        tenant: TenantId,
        entityRef: String,
        period: String,
    ): VerificationOutcome =
        try {
            verify(tenant, entityRef, period)
        } catch (_: Exception) {
            VerificationOutcome(RunResult.FAILED, RunCause.UNREADABLE, 0, null, emptyList())
        }

    private fun verify(
        tenant: TenantId,
        entityRef: String,
        period: String,
    ): VerificationOutcome {
        val entries = digests.digests(tenant, entityRef, period)
        val failures =
            chains.kinds(tenant, entityRef, period).flatMap { chainFailures(tenant, entityRef, period, it) } +
                entries.flatMap { digestFailures(tenant, it) }
        return VerificationOutcome(
            if (failures.isEmpty()) RunResult.VERIFIED else RunResult.FAILED,
            RunCause.VERIFICATION,
            chainLength(tenant, entityRef, period),
            entries.lastOrNull()?.digest,
            failures,
        )
    }

    private fun chainFailures(
        tenant: TenantId,
        entityRef: String,
        period: String,
        kind: RecordKind,
    ): List<ChainFailure> =
        when (val verified = checks.verify(tenant, entityRef, period, kind)) {
            is ChainVerificationResult.Valid -> {
                emptyList()
            }

            is ChainVerificationResult.Broken -> {
                val facts = chains.facts(tenant, entityRef, period, kind)
                // a broken chain has records; an empty one is an unreadable state and fails closed as UNREADABLE
                val from = checkNotNull(facts.firstAt) { "a broken chain has a first record" }
                val to = checkNotNull(facts.lastAt) { "a broken chain has a last record" }
                listOf(ChainFailure(kind, FailureKind.BROKEN_CHAIN, verified.atSequence, from, to))
            }
        }

    /** The checks of one `period_digest` row: tail of each chain, the recomputed digest and the object store copy. */
    private fun digestFailures(
        tenant: TenantId,
        entry: PeriodDigestEntry,
    ): List<ChainFailure> {
        val tails =
            entry.chains.filter { it.length > 0 }.mapNotNull { tail ->
                val kind = tail.type.kind
                val facts = chains.facts(tenant, entry.entityRef, entry.period, kind)
                val intact =
                    facts.length >= tail.length &&
                        chains.hashAt(tenant, entry.entityRef, entry.period, kind, tail.length - 1) == tail.tailHash
                if (intact) null else failure(tenant, entry, kind, FailureKind.DIGEST_MISMATCH)
            }
        val recomputed =
            runCatching { periodDigest(entry.entityRef, entry.period, entry.returnVersion, entry.chains) }.getOrNull()
        val digestFault =
            if (recomputed == entry.digest) emptyList() else perChain(tenant, entry, FailureKind.DIGEST_MISMATCH)
        val copyFault =
            if (copies.matches(
                    tenant,
                    entry,
                )
            ) {
                emptyList()
            } else {
                perChain(tenant, entry, FailureKind.RETAINED_COPY_MISMATCH)
            }
        return tails + digestFault + copyFault
    }

    /** One failure per chain type in the fixed order (INV-Q-12986 A). */
    private fun perChain(
        tenant: TenantId,
        entry: PeriodDigestEntry,
        failure: FailureKind,
    ): List<ChainFailure> = DigestChainType.entries.map { failure(tenant, entry, it.kind, failure) }

    /** The range is the first to the last `recordedAt` of the chain, or the digest's `recordedAt` for an empty one. */
    private fun failure(
        tenant: TenantId,
        entry: PeriodDigestEntry,
        kind: RecordKind,
        failure: FailureKind,
    ): ChainFailure {
        val facts = chains.facts(tenant, entry.entityRef, entry.period, kind)
        return ChainFailure(kind, failure, null, facts.firstAt ?: entry.recordedAt, facts.lastAt ?: entry.recordedAt)
    }

    private fun chainLength(
        tenant: TenantId,
        entityRef: String,
        period: String,
    ): Long {
        val kinds = if (isAuditPeriod(period)) listOf(RecordKind.AUDIT_LOG) else DigestChainType.entries.map { it.kind }
        return kinds.sumOf { chains.facts(tenant, entityRef, period, it).length }
    }
}
