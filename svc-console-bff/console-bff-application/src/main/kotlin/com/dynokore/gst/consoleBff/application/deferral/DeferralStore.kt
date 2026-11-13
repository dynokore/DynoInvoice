package com.dynokore.gst.consoleBff.application.deferral

import com.dynokore.gst.consoleBff.domain.deferral.Deferral
import com.dynokore.gst.consoleBff.domain.deferral.DeferralTarget
import java.util.UUID

/** Port of the deferral marker. It takes no tenant: the adapter's database binding isolates tenants. */
interface DeferralStore {
    /**
     * Returns the existing DEFERRED row of the same (entityRef, target kind, target ref) unchanged,
     * else stores [candidate] (state DEFERRED) and returns it.
     */
    fun deferIfAbsent(candidate: Deferral): Deferral

    /** Sets the DEFERRED row with this id and entityRef to UNDONE and returns it; null when no such row is DEFERRED. */
    fun undo(
        entityRef: String,
        deferralId: UUID,
    ): Deferral?

    fun findDeferred(
        entityRef: String,
        target: DeferralTarget,
    ): Deferral?
}
