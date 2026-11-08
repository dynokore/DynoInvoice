package com.dynokore.gst.tenancy.domain

/** The system that renders a document class (INV-REQ-1031): the entity itself, its agent or an aggregator. */
enum class Renderer {
    SELF,
    AGENT,
    AGGREGATOR,
    ;

    companion object {
        /** The renderer named exactly [text] (case-sensitive, no trimming); null for anything else. */
        fun parse(text: String?): Renderer? = entries.firstOrNull { it.name == text }
    }
}

/** The state of the attestation of one document class (INV-REQ-1034): a PAY-014 finding requests it again. */
enum class AttestationState {
    CURRENT,
    REQUESTED_AGAIN,
}

/** The rules on the attestation that need no store (INV-Q-16391, INV-Q-16397). */
object RenditionRules {
    /** The attester must be the authenticated caller's user ID, compared by exact equality (INV-Q-16397). */
    fun attesterIsCaller(
        attestedBy: String,
        caller: String,
    ): Boolean = caller.isNotEmpty() && attestedBy == caller

    /** A finding event sets REQUESTED_AGAIN when it is open and changes nothing when it is closed (INV-Q-16391). */
    fun stateAfterFinding(open: Boolean): AttestationState? = if (open) AttestationState.REQUESTED_AGAIN else null

    /** An event is applied only when its [sequence] is above the last one stored for the entity. */
    fun isNewer(
        sequence: Long,
        lastStored: Long?,
    ): Boolean = lastStored == null || sequence > lastStored
}
