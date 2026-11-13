package com.dynokore.gst.consoleBff.application.duedates

import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext

/**
 * Applies a `PeriodDueDateChanged` event in version order (INV-AC-21076): APPLIED when the store wrote, IGNORED for an
 * equal or lower version. A malformed event is a `ContractViolation` raised by the reader (nothing written).
 */
class PeriodDueDateChangedHandler(
    private val tenants: TenantContext,
    private val reader: PeriodDueDateChangedReader,
    private val store: PeriodDueDateStore,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val change = reader.read(event.dataJson)
        val wrote =
            store.applyIfNewer(
                tenants.current().value.toString(),
                change.key,
                change.newDueDate,
                change.version,
            )
        return if (wrote) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}
