package com.dynokore.gst.access.application.authorise

import com.dynokore.gst.access.domain.grant.Decision
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Target
import com.dynokore.gst.access.domain.role.Permission

/** Whether the structure replica holds its snapshot, so that an unknown entity is a definite answer (INV-AC-9089). */
fun interface StructureReadiness {
    fun isSynced(): Boolean
}

/** The result of the gated authorise: a decision, or no decision at all because the structure is not synced yet. */
sealed interface AuthoriseOutcome {
    data class Decided(
        val decision: Decision,
    ) : AuthoriseOutcome

    /** The replica holds no snapshot; nothing was decided. It is not a [Decision.Deny] and holds no reason code. */
    data object NotReady : AuthoriseOutcome
}

/**
 * Puts the readiness of the structure replica in front of [Authorise]: while it is not synced nothing is decided and
 * [Authorise] is not called, so an empty structure never turns into a DENY (INV-Q-17134 A, INV-AC-9089).
 */
class AuthoriseGate(
    private val readiness: StructureReadiness,
    private val authorise: Authorise,
) {
    fun handle(
        principal: IdentityRef,
        permission: Permission,
        target: Target,
    ): AuthoriseOutcome =
        if (readiness.isSynced()) {
            AuthoriseOutcome.Decided(authorise.handle(principal, permission, target))
        } else {
            AuthoriseOutcome.NotReady
        }
}
