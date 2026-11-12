package com.dynokore.gst.submission.application.period

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext

/**
 * Applies `PeriodRecordsChanged` (INV-REQ-570): an idempotent receiver by version. An event whose version is not above
 * the highest stored version is ignored. A malformed period set is a [ContractViolation] (dead letter at once) and
 * writes nothing. Store failures propagate; nothing is caught here. Messages carry no field value.
 */
class PeriodRecordsChangedHandler(
    private val tenants: TenantContext,
    private val reader: PeriodRecordsChangedDataReader,
    private val store: PeriodRecordStore,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = reader.read(event.dataJson)
        val tenant = tenants.current()
        val entityRef = event.entity.value
        val highest = store.highestVersion(tenant, entityRef)
        if (highest != null && data.version <= highest) return HandleOutcome.IGNORED
        malformation(data.periods)?.let { throw ContractViolation("PeriodRecordsChanged version ${data.version}: $it") }
        store.replace(tenant, entityRef, data.version, data.periods)
        return HandleOutcome.APPLIED
    }

    private fun malformation(periods: List<PeriodRecord>): String? =
        when {
            periods.isEmpty() -> "no period"
            periods.any { it.periodEnd.isBefore(it.periodStart) } -> "a period ends before it starts"
            periods.map { it.periodRef }.toSet().size != periods.size -> "a period reference is repeated"
            else -> null
        }
}
