package com.dynokore.gst.submission.application.register

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import java.time.LocalDate

/** The members of a `PeriodDueDateChanged` event the consumer reads; [dueDateVersion] is at least 1. */
data class PeriodDueDateChangedData(
    val entityRef: String,
    val period: String,
    val newFilingDueDate: LocalDate,
    val dueDateVersion: Long,
)

/** Reads the event data JSON of a `PeriodDueDateChanged` (the JSON library stays in the adapters, ARC-03). */
fun interface PeriodDueDateChangedDataReader {
    /** @throws ContractViolation when a member is missing or malformed (dead letter at once). */
    fun read(dataJson: String): PeriodDueDateChangedData
}

/**
 * Keeps the filing due date of a period from `PeriodDueDateChanged` (INV-REQ-570, INV-T-20514). Ordering is the
 * payload's `dueDateVersion` per tenant, entity and period: a row changes only for a higher version, so a re-delivery
 * or a late older event is IGNORED. A malformed event is a [ContractViolation] and writes nothing. No message
 * carries a field value.
 */
class PeriodDueDateChangedHandler(
    private val tenants: TenantContext,
    private val reader: PeriodDueDateChangedDataReader,
    private val dueDates: PeriodDueDates,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = reader.read(event.dataJson)
        return when (
            dueDates.upsert(
                tenants.current(),
                data.entityRef,
                data.period,
                data.newFilingDueDate,
                data.dueDateVersion,
            )
        ) {
            UpsertResult.INSERTED, UpsertResult.UPDATED -> HandleOutcome.APPLIED
            UpsertResult.UNCHANGED -> HandleOutcome.IGNORED
        }
    }
}
