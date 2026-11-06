package com.dynokore.gst.controlPlane.domain.accreditation

import java.time.Instant

enum class TaskStatus { OPEN, CONCLUDED }

/** Why a task was opened (INV-REQ-1365): the lapse wins when both hold (INV-T-7323). */
enum class TaskReason {
    ACCREDITATION_LAPSED,
    REASSESSMENT_NEEDED,
    ;

    companion object {
        /**
         * [ACCREDITATION_LAPSED] when the accreditation is [Currency.LAPSED], else [REASSESSMENT_NEEDED] when the
         * request needs an assessment, else null: no task. NOT_RECORDED alone raises nothing.
         */
        fun of(
            currency: Currency,
            assessmentRequested: Boolean,
        ): TaskReason? =
            when {
                currency == Currency.LAPSED -> ACCREDITATION_LAPSED
                assessmentRequested -> REASSESSMENT_NEEDED
                else -> null
            }
    }
}

/** A tracked re-accreditation assessment (INV-REQ-1365): never a release gate. */
data class AccreditationTask(
    val taskRef: String,
    val status: TaskStatus,
    val reason: TaskReason,
    val openedAt: Instant,
    val alertRef: String,
    val releaseRef: String?,
    val concludedAt: Instant? = null,
    val conclusion: String? = null,
) {
    init {
        require(
            taskRef.length in 1..MAX_REF && alertRef.length in 1..MAX_REF,
        ) { "a reference is 1..$MAX_REF characters" }
        require((status == TaskStatus.CONCLUDED) == (concludedAt != null && conclusion != null)) {
            "concludedAt and conclusion are present exactly when the task is CONCLUDED"
        }
        require((concludedAt == null) == (conclusion == null)) { "concludedAt and conclusion go together" }
    }

    fun conclude(
        at: Instant,
        text: String,
    ) = copy(status = TaskStatus.CONCLUDED, concludedAt = at, conclusion = text)

    companion object {
        const val MAX_REF = 128
        const val MAX_CONCLUSION = 2000
    }
}
