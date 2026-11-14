package com.dynokore.gst.submission.application.completeness

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import java.time.LocalDate

/** The `state` of an `AggregateWindowChanged` event. */
enum class WindowChangeState { OPEN, CLOSED, LATE }

/** The members of an `AggregateWindowChanged` event the consumer reads; [stateVersion] is 0 when absent. */
data class AggregateWindowChangedData(
    val entityRef: String,
    val prescribedPeriod: String,
    val from: LocalDate,
    val to: LocalDate,
    val state: WindowChangeState,
    val stateVersion: Long,
)

/** Reads the event data JSON of an `AggregateWindowChanged` (the JSON library stays in the adapters, ARC-03). */
fun interface AggregateWindowChangedDataReader {
    /** @throws ContractViolation when a member (including window from or to) is missing or malformed. */
    fun read(dataJson: String): AggregateWindowChangedData
}

/**
 * Keeps the aggregate window rows from `AggregateWindowChanged` (INV-REQ-792, ruled cases 4 and 5): LATE stores the
 * part as failing, CLOSED as not failing, OPEN as open; a row changes only for a higher stateVersion. A malformed
 * event is a [ContractViolation] and writes nothing. No message carries a field value.
 */
class AggregateWindowChangedHandler(
    private val tenants: TenantContext,
    private val reader: AggregateWindowChangedDataReader,
    private val states: WindowPartStates,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = reader.read(event.dataJson)
        val stored =
            when (data.state) {
                WindowChangeState.LATE -> StoredState.FAILING
                WindowChangeState.CLOSED -> StoredState.NOT_FAILING
                WindowChangeState.OPEN -> StoredState.OPEN
            }
        val changed =
            states.record(
                tenants.current(),
                data.entityRef,
                data.prescribedPeriod,
                data.from,
                data.to,
                stored,
                data.stateVersion,
            )
        return if (changed) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}
