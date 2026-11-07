package com.dynokore.gst.masterdata.application.identity

import com.dynokore.gst.masterdata.domain.identity.ConfirmerSet
import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.PendingChange
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.gst.masterdata.domain.identity.TripleVersion
import com.dynokore.platform.serviceruntime.replica.DecisionSource

/** The stored triples of the bound tenant (TPL-11); every call runs inside the caller's transaction. */
interface TripleRepository {
    /** Every version of the entity under the constitution, oldest first. */
    fun versions(
        entity: EntityRef,
        constitution: Constitution,
    ): List<TripleVersion>

    /** Stores [version]; a repeat of (entity, constitution, effectiveFrom) replaces the stored one (repeatKey). */
    fun save(
        version: TripleVersion,
        proposedBy: PersonRef,
        confirmedBy: PersonRef,
    )
}

/** The proposed changes of the bound tenant; they are never read by the resolver, so a pending one is not in force. */
interface PendingChangeRepository {
    /**
     * Stores a new PENDING_CONFIRMATION change and supersedes an earlier pending one of the same entity, constitution
     * and effective date (repeatKey of `configureIdentityTriple`).
     */
    fun propose(
        version: TripleVersion,
        proposedBy: PersonRef,
    ): PendingChange

    /** The change [changeRef] of [entity], whatever its state; null when the entity has no such change. */
    fun find(
        entity: EntityRef,
        changeRef: String,
    ): PendingChange?

    /**
     * Moves the change from PENDING_CONFIRMATION to CONFIRMED; false when it was not pending any more (a concurrent
     * confirmation or a superseding proposal won).
     */
    fun markConfirmed(
        change: PendingChange,
        confirmedBy: PersonRef,
    ): Boolean
}

/**
 * The tenant's confirmer set (VP-09), read at TENANT scope. The owner answer INV-Q-7880 A fixes the set while the
 * control is `planned`; the application only reads the port.
 */
fun interface ConfirmerPolicy {
    fun confirmers(): ConfirmerSet
}

/**
 * The answer of the permission `entity-config.confirm` for one subject at one entity: [allowed] and the [source] of the
 * authority decision it was built from (INV-Q-10025: only LOCAL and FALLBACK count).
 */
data class ConfirmerDecision(
    val allowed: Boolean,
    val source: DecisionSource,
)

/**
 * Whether the authenticated [subject] holds the permission `entity-config.confirm` at [entity], decided per request by
 * the authority replica (INV-Q-10027, INV-Q-8121). No role name crosses this port.
 */
fun interface ConfirmerPermission {
    fun decide(
        subject: PersonRef,
        entity: EntityRef,
    ): ConfirmerDecision
}

/**
 * Publishes `IdentityTripleCorrected` for a back-dated change that has just taken effect (INV-REQ-1006, owner answer
 * INV-Q-7876 A). It appends in the transaction the caller holds, so a failure rolls the confirmation back.
 */
fun interface IdentityTripleCorrectedPublisher {
    fun publish(change: PendingChange)
}

/**
 * Publishes `IdentityTripleChanged` for a confirmed identity triple change (INV-REQ-1135, INV-Q-16401 A). It appends in
 * the transaction the caller holds, so a failure rolls the confirmation back.
 */
fun interface IdentityTripleChangedPublisher {
    fun publish(change: PendingChange)
}
