package com.dynokore.gst.evidence.domain.cases

import java.time.Instant
import java.util.UUID

/** The kind of a case record: a privacy request or a recorded security review. */
enum class CaseKind { PRIVACY, SECURITY }

/** The status of a case; the members equal the enum of `PrivacyCase.status` in the evidence contract. */
enum class CaseStatus { OPEN, EXPORT_READY, CLOSED }

/** The facts of a security case (`SecurityCase` in the evidence contract). The list keeps its order and duplicates. */
data class SecurityCaseFacts(
    val reviewReference: String,
    val auditLogEntryIds: List<UUID>,
    val outcome: String,
    val breachAssessmentOpened: Boolean,
) {
    init {
        require(reviewReference.length <= MAX_REVIEW_REFERENCE) { "reviewReference is too long" }
        require(outcome.length <= MAX_OUTCOME) { "outcome is longer than $MAX_OUTCOME" }
        require(auditLogEntryIds.isNotEmpty()) { "auditLogEntryIds must hold at least one id" }
    }

    companion object {
        const val MAX_REVIEW_REFERENCE = 100
        const val MAX_OUTCOME = 200
    }
}

/** One case of `evidence-svc`. A SECURITY case has no subject and carries [security]; a PRIVACY case has a subject. */
data class CaseRecord(
    val caseId: UUID,
    val kind: CaseKind,
    val status: CaseStatus,
    val subjectRef: String?,
    val openedAt: Instant,
    val security: SecurityCaseFacts?,
) {
    init {
        when (kind) {
            CaseKind.SECURITY -> {
                require(security != null) { "a SECURITY case needs its security facts" }
                require(subjectRef == null) { "a SECURITY case has no subjectRef" }
                require(status == CaseStatus.OPEN) { "a SECURITY case opens with status OPEN" }
            }

            CaseKind.PRIVACY -> {
                require(security == null) { "a PRIVACY case has no security facts" }
                require(subjectRef != null) { "a PRIVACY case needs a subjectRef" }
                require(subjectRef.length <= MAX_SUBJECT_REF) { "subjectRef is longer than $MAX_SUBJECT_REF" }
            }
        }
    }

    companion object {
        const val MAX_SUBJECT_REF = 60
    }
}
