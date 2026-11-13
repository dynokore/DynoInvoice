package com.dynokore.gst.consoleBff.domain.deferral

import java.time.Instant
import java.util.UUID

/** Kind of the deferred target (contract `DeferralTarget.kind`). */
enum class DeferralKind { DOCUMENT, GROUP }

/** State of a deferral (contract `Deferral.state`). */
enum class DeferralState { DEFERRED, UNDONE }

/** Contract `DeferralTarget`: kind and a reference of 1..128 characters. */
data class DeferralTarget(
    val kind: DeferralKind,
    val ref: String,
) {
    init {
        require(ref.length in 1..MAX_REF) { "target ref must be 1..$MAX_REF characters" }
    }

    companion object {
        const val MAX_REF = 128
    }
}

/**
 * Contract `Deferral`. The contract has no until and no reason member, so none is stored.
 * `deferredBy` is the acting user ID.
 */
data class Deferral(
    val deferralId: UUID,
    val entityRef: String,
    val target: DeferralTarget,
    val state: DeferralState,
    val deferredBy: String,
    val deferredAt: Instant,
) {
    init {
        require(entityRef.length in 1..MAX_ENTITY_REF) { "entityRef must be 1..$MAX_ENTITY_REF characters" }
        require(deferredBy.length in 1..MAX_DEFERRED_BY && deferredBy.isNotBlank()) {
            "deferredBy must be 1..$MAX_DEFERRED_BY characters and not blank"
        }
    }

    companion object {
        const val MAX_ENTITY_REF = 40
        const val MAX_DEFERRED_BY = 128
    }
}
