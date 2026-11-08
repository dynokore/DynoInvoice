package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.EntityRef

/**
 * `failoverAdapter` (INV-REQ-796): cuts the entity named by the request over to its second adapter [target]. The
 * assignment itself is made by [SwitchEntityAdapter], the one place that assigns (INV-AC-846 cut-over): new despatches
 * of the entity use [target], a version in flight stays on the adapter of its first attempt ([SubmitPayload]), and no
 * other entity and no submission state held by the caller is touched. The call is idempotent; the entity comes from
 * the request and is never inferred.
 */
class FailoverAdapter(
    private val switchEntityAdapter: SwitchEntityAdapter,
) {
    fun failover(
        entity: EntityRef,
        target: AdapterId,
    ) = switchEntityAdapter.switch(entity, target)
}
