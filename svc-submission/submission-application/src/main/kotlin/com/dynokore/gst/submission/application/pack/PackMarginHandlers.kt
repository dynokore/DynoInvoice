package com.dynokore.gst.submission.application.pack

import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext

private const val CALENDAR = "calendar"

private fun outcome(inserted: Boolean): HandleOutcome = if (inserted) HandleOutcome.APPLIED else HandleOutcome.IGNORED

/**
 * Stores the safety margin of a staged calendar pack version once (event-carried state transfer). An absent or
 * invalid margin is stored as no margin (fail closed). Other pack types are ignored. Nothing is caught: a malformed
 * event is a `ContractViolation` from the reader and a store failure rolls the consumer transaction back.
 */
class CalendarPackStagedMarginHandler(
    private val tenants: TenantContext,
    private val reader: PackStagedDataReader,
    private val store: PackMarginStore,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = reader.read(event.dataJson)
        if (data.packType != CALENDAR) return HandleOutcome.IGNORED
        val margin = data.margin?.let { PackMargin.of(it.value, it.unit) }
        return outcome(store.stageCalendar(tenants.current(), data.packIdentifier, data.version, margin))
    }
}

/** Records the activation of every pack identifier and version; a redelivery changes nothing. */
class PackActivatedHandler(
    private val tenants: TenantContext,
    private val reader: PackActivatedDataReader,
    private val store: PackMarginStore,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = reader.read(event.dataJson)
        return outcome(store.recordActivation(tenants.current(), data.packIdentifier, data.version, data.effectiveFrom))
    }
}
