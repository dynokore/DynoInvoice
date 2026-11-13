package com.dynokore.gst.consoleBff.application.deferral

import com.dynokore.gst.consoleBff.domain.deferral.Deferral
import com.dynokore.gst.consoleBff.domain.deferral.DeferralKind
import com.dynokore.gst.consoleBff.domain.deferral.DeferralState
import com.dynokore.gst.consoleBff.domain.deferral.DeferralTarget
import java.time.Clock
import java.util.UUID

/** deferDocument: authority first (fail closed), repeat returns the stored row, a missing target is NotFound. */
class DeferTarget(
    private val authority: DeferAuthority,
    private val targets: DeferralTargets,
    private val store: DeferralStore,
    private val clock: Clock,
    private val ids: () -> UUID,
) {
    fun execute(
        tenantId: String,
        userId: String,
        entityRef: String,
        target: DeferralTarget,
    ): DeferResult =
        when {
            !authority.holds(tenantId, userId, entityRef, DeferPermissions.EXCEPTION_DEFER) -> DeferResult.Forbidden
            else -> afterAuthority(userId, entityRef, target)
        }

    private fun afterAuthority(
        userId: String,
        entityRef: String,
        target: DeferralTarget,
    ): DeferResult {
        val existing = store.findDeferred(entityRef, target)
        val exists =
            existing != null ||
                when (target.kind) {
                    DeferralKind.DOCUMENT -> targets.openDocument(entityRef, target.ref)
                    DeferralKind.GROUP -> targets.openGroup(entityRef, target.ref)
                }
        return when {
            existing != null -> {
                DeferResult.Deferred(existing)
            }

            !exists -> {
                DeferResult.NotFound
            }

            else -> {
                DeferResult.Deferred(
                    store.deferIfAbsent(
                        Deferral(ids(), entityRef, target, DeferralState.DEFERRED, userId, clock.instant()),
                    ),
                )
            }
        }
    }
}
