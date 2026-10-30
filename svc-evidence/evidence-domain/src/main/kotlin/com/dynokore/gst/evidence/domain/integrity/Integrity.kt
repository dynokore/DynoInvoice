package com.dynokore.gst.evidence.domain.integrity

import com.dynokore.gst.evidence.domain.chain.AuditChainKey
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.gst.evidence.domain.digest.DigestChainType
import java.time.Instant

/** The result of one verification run of an (entity, period) (INV-Q-7251 A (4)). */
enum class RunResult { VERIFIED, FAILED }

/** Why a run has its result: the verification itself, or a chain or copy that could not be read (INV-Q-12988 A). */
enum class RunCause { VERIFICATION, UNREADABLE }

/** The evidence types of the document-bound chains, in the fixed order of the period digest (INV-Q-7251 A (2)). */
enum class EvidenceType { PAYLOAD, ACKNOWLEDGEMENT, DECISION_TRACE }

/** What a failed verification found (INV-Q-7253 A (1)). */
enum class FailureKind { BROKEN_CHAIN, DIGEST_MISMATCH, RETAINED_COPY_MISMATCH }

/** The integrity status of an entity and period: the result of the latest run, or [NEVER_RUN] (INV-REQ-661). */
enum class IntegrityState { VERIFIED, FAILED, NEVER_RUN }

/** The integrity status `getIntegrityStatus` answers; [lastRunAt] and [periodDigest] are absent when never run. */
data class IntegrityStatus(
    val result: IntegrityState,
    val lastRunAt: Instant?,
    val chainLength: Long,
    val periodDigest: String?,
) {
    companion object {
        val NEVER_RUN = IntegrityStatus(IntegrityState.NEVER_RUN, null, 0, null)
    }
}

/** The evidence type name of a chain as `integrity_failure` and `EvidenceIntegrityFailed` write it. */
val RecordKind.evidenceType: String
    get() =
        when (this) {
            RecordKind.EVIDENCE_PAYLOAD -> "PAYLOAD"
            RecordKind.ACKNOWLEDGEMENT -> "ACKNOWLEDGEMENT"
            RecordKind.DECISION_TRACE -> "DECISION_TRACE"
            RecordKind.AUDIT_LOG -> "AUDIT_LOG"
        }

/** The evidence type of a digest chain type. */
val DigestChainType.evidenceType: EvidenceType
    get() =
        when (this) {
            DigestChainType.PAYLOAD -> EvidenceType.PAYLOAD
            DigestChainType.ACKNOWLEDGEMENT -> EvidenceType.ACKNOWLEDGEMENT
            DigestChainType.DECISION_TRACE -> EvidenceType.DECISION_TRACE
        }

/** The chain table a digest chain type is read from. */
val DigestChainType.kind: RecordKind
    get() =
        when (this) {
            DigestChainType.PAYLOAD -> RecordKind.EVIDENCE_PAYLOAD
            DigestChainType.ACKNOWLEDGEMENT -> RecordKind.ACKNOWLEDGEMENT
            DigestChainType.DECISION_TRACE -> RecordKind.DECISION_TRACE
        }

/** True for the period key of an audit chain (`AUDIT-yyyy-MM`, INV-Q-7213 A (4)), which has no period digest. */
fun isAuditPeriod(period: String): Boolean = period.startsWith(AuditChainKey.PREFIX)
