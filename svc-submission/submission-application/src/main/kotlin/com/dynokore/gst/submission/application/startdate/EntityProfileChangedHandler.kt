package com.dynokore.gst.submission.application.startdate

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import java.time.LocalDate

/**
 * The members of an `EntityProfileChanged` event the consumer reads. [obligationStartDate] is null when no changed
 * attribute is named exactly `obligationStartDate`.
 */
data class EntityProfileChangedData(
    val entityRef: String,
    val obligationStartDate: LocalDate?,
    val effectiveDate: LocalDate,
)

/** Reads the event data JSON of an `EntityProfileChanged` (the JSON library stays in the adapters, ARC-03). */
fun interface EntityProfileChangedDataReader {
    /** @throws ContractViolation when a member is missing or malformed (dead letter at once). */
    fun read(dataJson: String): EntityProfileChangedData
}

/**
 * Keeps the obligation start date of an entity from `EntityProfileChanged` (INV-REQ-335, INV-T-22201). The latest
 * `effectiveDate` wins; an older or equal one, or an event without the attribute, is IGNORED. A malformed event is a
 * [ContractViolation] and writes nothing. No message carries a field value.
 */
class EntityProfileChangedHandler(
    private val tenants: TenantContext,
    private val reader: EntityProfileChangedDataReader,
    private val startDates: ObligationStartDates,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = reader.read(event.dataJson)
        val startDate = data.obligationStartDate ?: return HandleOutcome.IGNORED
        return if (startDates.record(tenants.current(), data.entityRef, startDate, data.effectiveDate)) {
            HandleOutcome.APPLIED
        } else {
            HandleOutcome.IGNORED
        }
    }
}
