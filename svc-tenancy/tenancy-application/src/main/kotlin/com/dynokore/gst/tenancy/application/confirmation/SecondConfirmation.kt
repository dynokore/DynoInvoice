package com.dynokore.gst.tenancy.application.confirmation

import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.domain.ElectionPermissions

/** The verdict of a second confirmation (INV-REQ-875, owner answer INV-Q-7985 B). */
enum class ConfirmationVerdict {
    ACCEPTED,

    /** The confirmer is the caller: the initiator or the same person under another role. */
    SAME_PERSON,

    /** The confirmer does not hold `entity-config.confirm` for the entity, or the replica could not answer. */
    NOT_PERMITTED,
}

/**
 * The second confirmation of the go-live of an entity and of offboarding with scope ENTITY, decided in this service
 * with no call to another service: the principal reference of the confirmer differs from the caller's by equality,
 * and the confirmer holds `entity-config.confirm` for the entity, read through the authority replica. The
 * sole-operator path is not read: rule (a) applies in every tenant (fail closed until the replica record exists).
 */
class SecondConfirmation(
    private val authority: ActionAuthority,
) {
    fun verdict(
        caller: String,
        confirmer: String,
        entityRef: String,
    ): ConfirmationVerdict =
        when {
            confirmer == caller -> ConfirmationVerdict.SAME_PERSON
            !holds(confirmer, entityRef) -> ConfirmationVerdict.NOT_PERMITTED
            else -> ConfirmationVerdict.ACCEPTED
        }

    private fun holds(
        person: String,
        entityRef: String,
    ): Boolean =
        try {
            authority.holds(person, entityRef, ElectionPermissions.CONFIRM)
        } catch (_: RuntimeException) {
            false
        }
}
