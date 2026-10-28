package com.dynokore.gst.access.application.separation

import com.dynokore.gst.access.domain.role.Role

/** The pairs a tenant lists in VP-82 (INV-REQ-9271); they only add refusals to the built-in pair. */
object ConflictingPairPolicy {
    /**
     * Whether [adding] to a person who already holds [held] (revoked grants not counted) completes a pair of [pairs]:
     * a pair is unordered, so it counts whichever of its two roles is added.
     */
    fun completesListedPair(
        pairs: Set<RolePair>,
        held: Set<Role>,
        adding: Role,
    ): Boolean = pairs.any { adding in it.roles && (it.roles - adding).any { other -> other in held } }
}
