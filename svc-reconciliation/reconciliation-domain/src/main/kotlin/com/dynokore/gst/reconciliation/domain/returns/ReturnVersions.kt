package com.dynokore.gst.reconciliation.domain.returns

import java.time.Instant

/** A return version already recorded for one (entity, period). */
data class RecordedReturn(
    val version: Int,
    val form: ReturnForm,
    val filedAt: Instant,
    val filingAcknowledgement: String,
    val amends: Int?,
)

/** A return upload as received; the acknowledgement is optional in the schema only so that a 1.0 client validates. */
data class ReturnUpload(
    val version: Int,
    val form: ReturnForm,
    val filedAt: Instant,
    val filingAcknowledgement: String?,
    val amends: Int?,
)

/** Why an upload is refused. */
enum class RefusalReason(
    val code: String?,
) {
    /** INV-REQ-653 b2, INV-AC-1715. */
    MISSING_FILING_ACKNOWLEDGEMENT("PER-014"),

    /** No catalogue code: the status of this refusal is the endpoint slice's. */
    VERSION_LOWER_THAN_RECORDED(null),
}

sealed interface UploadDecision {
    data object Accepted : UploadDecision

    /** The version is already recorded: the original result, unchanged. */
    data class Repeat(
        val original: RecordedReturn,
    ) : UploadDecision

    data class Refused(
        val reason: RefusalReason,
    ) : UploadDecision
}

/** Decisions over the recorded versions of a return (INV-REQ-653 b3, INV-REQ-656 b1); pure, no I/O, no clock. */
object ReturnVersions {
    fun decide(
        recorded: List<RecordedReturn>,
        upload: ReturnUpload,
    ): UploadDecision {
        val same = recorded.firstOrNull { it.version == upload.version }
        return when {
            upload.filingAcknowledgement.isNullOrBlank() -> {
                UploadDecision.Refused(RefusalReason.MISSING_FILING_ACKNOWLEDGEMENT)
            }

            same != null -> {
                UploadDecision.Repeat(same)
            }

            recorded.any { it.version > upload.version } -> {
                UploadDecision.Refused(RefusalReason.VERSION_LOWER_THAN_RECORDED)
            }

            else -> {
                UploadDecision.Accepted
            }
        }
    }

    /** The return reconciliation runs against: the recorded return of the highest version, or null when none. */
    fun current(recorded: List<RecordedReturn>): RecordedReturn? = recorded.maxByOrNull { it.version }

    /** Versions of recorded F5 returns amended by a recorded F7; an `amends` naming no recorded F5 is ignored. */
    fun superseded(recorded: List<RecordedReturn>): Set<Int> {
        val f5Versions = recorded.filter { it.form == ReturnForm.F5 }.map { it.version }.toSet()
        return recorded
            .filter { it.form == ReturnForm.F7 }
            .mapNotNull { it.amends }
            .filter { it in f5Versions }
            .toSet()
    }
}
