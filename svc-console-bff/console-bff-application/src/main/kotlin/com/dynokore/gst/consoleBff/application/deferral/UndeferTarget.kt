package com.dynokore.gst.consoleBff.application.deferral

import java.util.UUID

/** undeferDocument: authority first (fail closed); only a DEFERRED row can be undone, else NotFound. */
class UndeferTarget(
    private val authority: DeferAuthority,
    private val store: DeferralStore,
) {
    fun execute(
        tenantId: String,
        userId: String,
        entityRef: String,
        deferralId: UUID,
    ): UndeferResult =
        when {
            !authority.holds(tenantId, userId, entityRef, DeferPermissions.EXCEPTION_DEFER) -> UndeferResult.Forbidden
            else -> store.undo(entityRef, deferralId)?.let { UndeferResult.Undone(it) } ?: UndeferResult.NotFound
        }
}
