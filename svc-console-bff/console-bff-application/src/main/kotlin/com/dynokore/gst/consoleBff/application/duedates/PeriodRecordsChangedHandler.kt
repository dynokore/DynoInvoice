package com.dynokore.gst.consoleBff.application.duedates

import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext

/**
 * Stores the initial due dates of a `PeriodRecordsChanged` event (INV-AC-21076) as version 0, insert-if-absent, so a
 * version-ordered value is never overwritten. APPLIED when at least one period was written.
 */
class PeriodRecordsChangedHandler(
    private val tenants: TenantContext,
    private val reader: PeriodRecordsChangedReader,
    private val store: PeriodDueDateStore,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val initial = reader.read(event.dataJson)
        val tenantId = tenants.current().value.toString()
        val wrote =
            initial.byPeriod.map { (period, date) ->
                store.putInitialIfAbsent(tenantId, PeriodKey(initial.entityRef, period), date)
            }
        return if (wrote.any { it }) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}
