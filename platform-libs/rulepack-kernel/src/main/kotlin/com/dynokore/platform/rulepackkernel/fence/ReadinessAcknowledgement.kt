package com.dynokore.platform.rulepackkernel.fence

import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackVersionLabel

/** Why a holder could not load a staged pack (INV-REQ-1166 behaviour #1). */
enum class ReadinessFailure {
    /** The signature, checksums or manifest did not verify against the train's trust anchors. */
    VERIFICATION_FAILED,

    /** The contents carry executable material (INV-AC-2394). */
    CONTENT_REFUSED,

    /** The holder could not compile the pack (its Schematron, for example). */
    COMPILE_FAILED,

    /** A different pack is already held under the same identity: a pack version is immutable. */
    CONFLICTING_PACK,
}

sealed interface ReadinessOutcome {
    /** Verified, compiled and held, not in use. */
    data object Loaded : ReadinessOutcome

    data class Failed(val reason: ReadinessFailure, val detail: String) : ReadinessOutcome
}

/**
 * The content of the platform event `PackReadinessAcknowledged`: pack identifier,
 * version, instance, outcome (events.yaml). Its schema is owned by `service-runtime`;
 * this is the shape a holder hands to the [ReadinessAcknowledger] port, which
 * service-runtime binds to the outbox. rulepack-kernel never calls outbox-and-queue
 * (INV-ADR-2774).
 */
data class PackReadinessAcknowledgement(
    val packId: PackId,
    val version: PackVersionLabel,
    val instance: String,
    val outcome: ReadinessOutcome,
)

fun interface ReadinessAcknowledger {
    fun acknowledge(acknowledgement: PackReadinessAcknowledgement)
}
