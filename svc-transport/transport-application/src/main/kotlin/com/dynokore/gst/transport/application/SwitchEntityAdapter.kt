package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.EntityRef

/** Points an entity at one of the registered adapters; versions in flight finish on the old one (INV-AC-846). */
class SwitchEntityAdapter(
    private val adapters: AccessPointAdapters,
    private val assignments: EntityAdapterAssignments,
) {
    fun switch(
        entity: EntityRef,
        adapter: AdapterId,
    ) {
        if (adapter !in adapters.ids()) throw AdapterNotRegistered(adapter.value)
        assignments.assign(entity, adapter)
    }
}
