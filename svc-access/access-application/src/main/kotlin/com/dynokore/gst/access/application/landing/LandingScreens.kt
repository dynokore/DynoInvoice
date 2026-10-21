package com.dynokore.gst.access.application.landing

import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.role.Landing
import com.dynokore.gst.access.domain.role.LandingPolicy
import com.dynokore.gst.access.domain.role.LandingSource
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock

/** A person's own landing choice, one per user under the bound tenant (INV-REQ-2756 behaviour 3). */
interface LandingChoices {
    fun find(identity: IdentityRef): Role?

    fun put(
        identity: IdentityRef,
        role: Role,
    )

    /** Removes the choice; a no-op when there is none. */
    fun clear(identity: IdentityRef)
}

/** The tenant-scope role-precedence policy (VP-73) as it resolves now: null while it is at its preset. */
fun interface RolePrecedence {
    fun tenantPrecedence(): List<Role>?
}

sealed interface ChooseLandingOutcome {
    data class Chosen(
        val landing: Landing,
    ) : ChooseLandingOutcome

    /** The role is not one the caller holds (or not a role): only held roles are candidates (INV-REQ-2756). */
    data object NotHeld : ChooseLandingOutcome
}

/**
 * `chooseLandingScreen`, `clearLandingChoice` and `resolveLandingScreen` (INV-REQ-2756). The person is the
 * authenticated caller. None of them reads or writes a permission or a grant, so no role's permissions change.
 */
class LandingScreens(
    private val tx: TransactionRunner,
    private val grants: GrantRepository,
    private val choices: LandingChoices,
    private val precedence: RolePrecedence,
    private val clock: Clock,
) {
    fun choose(
        identity: IdentityRef,
        roleWire: String,
    ): ChooseLandingOutcome =
        tx.inTransaction {
            val role = Role.ofWire(roleWire)
            val held = heldRoles(identity)
            if (role == null ||
                !LandingPolicy.isCandidate(held, role)
            ) {
                return@inTransaction ChooseLandingOutcome.NotHeld
            }
            if (choices.find(identity) != role) choices.put(identity, role)
            ChooseLandingOutcome.Chosen(Landing(role, LandingSource.PERSONAL_CHOICE))
        }

    fun clear(identity: IdentityRef) {
        tx.inTransaction { choices.clear(identity) }
    }

    /** The landing of [identity], or null when they hold no role. */
    fun resolve(identity: IdentityRef): Landing? =
        tx.inTransaction {
            LandingPolicy.resolve(heldRoles(identity), choices.find(identity), precedence.tenantPrecedence())
        }

    private fun heldRoles(identity: IdentityRef): Set<Role> {
        val now = clock.instant()
        return grants
            .findByIdentity(identity)
            .filter { it.status == GrantStatus.ACTIVE && it.window.isValidAt(now) }
            .map { it.role }
            .toSet()
    }
}
