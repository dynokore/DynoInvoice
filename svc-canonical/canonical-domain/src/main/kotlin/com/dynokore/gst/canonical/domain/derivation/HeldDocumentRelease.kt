package com.dynokore.gst.canonical.domain.derivation

/** Why a held document is not released (INV-Q-16154 A, INV-REQ-1315). */
enum class ReleaseRefusal {
    NOT_PERMITTED,
    NOT_MANUAL_HOLD,
    SAME_USER,
}

/** The decision of [HeldDocumentRelease.decide]. */
sealed interface HeldDocumentReleaseDecision {
    data object Released : HeldDocumentReleaseDecision

    data class Refused(
        val reason: ReleaseRefusal,
    ) : HeldDocumentReleaseDecision
}

/** Pure release rule for a held document: no state, no clock, no role name (separation of duties, AC-5). */
object HeldDocumentRelease {
    /** The hold codes a person may release: ING-017 (receipt correlation), ING-024 (debit note review). */
    val MANUAL_HOLD_CODES: Set<String> = setOf("ING-017", "ING-024")

    /**
     * [permitted] is the answer of `AuthorityReplica.decide` for `held-document.release`, computed by the caller.
     * [causedBy] is the user ID stored with the hold when a person's action caused it, else null. The first rule
     * that applies wins: not permitted, not a manual hold code, same user, otherwise released.
     */
    fun decide(
        holdCode: String,
        permitted: Boolean,
        releasedBy: String,
        causedBy: String?,
    ): HeldDocumentReleaseDecision {
        require(releasedBy.isNotBlank()) { "releasedBy must not be blank" }
        return when {
            !permitted -> HeldDocumentReleaseDecision.Refused(ReleaseRefusal.NOT_PERMITTED)
            holdCode !in MANUAL_HOLD_CODES -> HeldDocumentReleaseDecision.Refused(ReleaseRefusal.NOT_MANUAL_HOLD)
            causedBy != null && causedBy == releasedBy -> HeldDocumentReleaseDecision.Refused(ReleaseRefusal.SAME_USER)
            else -> HeldDocumentReleaseDecision.Released
        }
    }
}
