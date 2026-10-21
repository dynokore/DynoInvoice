package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.CeilingChange
import com.dynokore.gst.transport.domain.ThroughputCeiling
import java.math.BigDecimal

/** The throughput ceiling of an AP connection (INV-REQ-1317): the agreed rate, or a lower tenant value. */
class SetTenantCeiling(
    private val connections: ApConnections,
) {
    fun current(adapter: AdapterId): ThroughputCeiling =
        connections.ceiling(adapter) ?: throw AdapterNotRegistered(adapter.value)

    /** A value above the agreed rate, or not above zero, is refused and the stored ceiling stays as it was. */
    fun set(
        adapter: AdapterId,
        value: BigDecimal,
    ): CeilingChange {
        val change = current(adapter).withTenantValue(value)
        if (change is CeilingChange.Applied) connections.saveCeiling(adapter, change.ceiling)
        return change
    }
}
