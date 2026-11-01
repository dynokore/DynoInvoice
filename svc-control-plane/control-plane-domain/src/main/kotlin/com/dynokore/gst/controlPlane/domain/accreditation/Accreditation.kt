package com.dynokore.gst.controlPlane.domain.accreditation

import com.dynokore.gst.controlPlane.domain.pack.ActorId
import java.time.Instant
import java.time.LocalDate

/** The four submission types of the product's own sandbox acceptance (INV-REQ-1364). */
enum class SubmissionType { TYPE_1A, TYPE_1B, TYPE_2, TYPE_3 }

/** The IRAS sandbox Acknowledgement ID of the product's own submission of one type, independent of any tenant. */
data class SandboxAcknowledgement(
    val submissionType: SubmissionType,
    val acknowledgementId: String,
) {
    init {
        require(acknowledgementId.isNotBlank() && acknowledgementId.length <= MAX_ID) {
            "an acknowledgement id is 1..$MAX_ID characters"
        }
    }

    private companion object {
        const val MAX_ID = 128
    }
}

/**
 * One recorded accreditation (INV-REQ-1363). [recordedBy] is the user ID of the caller, never a role name
 * (INV-Q-10647).
 * A covered type may have no acknowledgement yet: acknowledgements may follow in a later record.
 */
data class AccreditationRecord(
    val grantedOn: LocalDate,
    val currentUntil: LocalDate,
    val imdaReference: String,
    val coveredSubmissionTypes: List<SubmissionType>,
    val sandboxAcknowledgements: List<SandboxAcknowledgement>,
    val recordedAt: Instant,
    val recordedBy: ActorId,
) {
    init {
        require(imdaReference.isNotBlank() && imdaReference.length <= MAX_REFERENCE) {
            "an IMDA reference is 1..$MAX_REFERENCE characters"
        }
        require(coveredSubmissionTypes.isNotEmpty() && coveredSubmissionTypes.distinct() == coveredSubmissionTypes) {
            "the covered submission types are one to four distinct types"
        }
        require(sandboxAcknowledgements.size <= SubmissionType.entries.size) { "at most one acknowledgement per type" }
    }

    /** The covered types that no acknowledgement of this record names (INV-AC-2339). */
    val typesWithoutAcknowledgement: List<SubmissionType>
        get() {
            val acknowledged = sandboxAcknowledgements.map { it.submissionType }.toSet()
            return coveredSubmissionTypes.filter { it !in acknowledged }
        }

    /** True when [currentUntil] is not before [asOf] (the last current day is [currentUntil] itself). */
    fun isCurrentOn(asOf: LocalDate): Boolean = !currentUntil.isBefore(asOf)

    private companion object {
        const val MAX_REFERENCE = 128
    }
}

enum class Currency { CURRENT, LAPSED, NOT_RECORDED }

/** The latest record and whether it is current on [asOf]; a status refuses and gates nothing (INV-AC-2338). */
data class AccreditationStatus(
    val currency: Currency,
    val asOf: LocalDate,
    val record: AccreditationRecord?,
) {
    companion object {
        fun of(
            latest: AccreditationRecord?,
            asOf: LocalDate,
        ) = AccreditationStatus(
            when {
                latest == null -> Currency.NOT_RECORDED
                latest.isCurrentOn(asOf) -> Currency.CURRENT
                else -> Currency.LAPSED
            },
            asOf,
            latest,
        )
    }
}
