package com.dynokore.gst.tenancy.application.period

import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.election.CallerSource

/**
 * The permission check of the period operations (INV-Q-10004, INV-Q-10006, INV-Q-10127 A): the caller of the request
 * (the end-user token's subject) must hold the permission key on the entity, as `AuthorityReplica.decide` answers it
 * through [ActionAuthority]. An exception and a denial refuse; in production without a replica the authority answers false.
 */
class PeriodAccess(
    private val authority: ActionAuthority,
    private val caller: CallerSource,
) {
    /** The reason the caller is told when [permission] is not held on [entityRef], or null when it is held. */
    fun denial(
        entityRef: String,
        permission: String,
    ): String? {
        val held =
            try {
                authority.holds(caller.actor(), entityRef, permission)
            } catch (_: RuntimeException) {
                false
            }
        return if (held) null else "the caller does not hold $permission for entity $entityRef"
    }
}
