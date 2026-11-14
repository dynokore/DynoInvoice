package com.dynokore.gst.submission.application.completeness

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext

/** The `state` of a `FeedHealthChanged` event. */
enum class FeedHealthState { FAILED, CLEARED }

/** The members of a `FeedHealthChanged` event the consumer reads; [stateVersion] is 0 when the member is absent. */
data class FeedHealthChangedData(
    val feed: String,
    val entityRef: String,
    val period: String,
    val control: String,
    val state: FeedHealthState,
    val stateVersion: Long,
)

/** Reads the event data JSON of a `FeedHealthChanged` (the JSON library stays in the adapters, ARC-03). */
fun interface FeedHealthChangedDataReader {
    /** @throws ContractViolation when a member is missing or malformed (dead letter at once). */
    fun read(dataJson: String): FeedHealthChangedData
}

/**
 * Keeps the feed control rows from `FeedHealthChanged` (INV-REQ-792, ruled cases 4 and 5): FAILED stores the control
 * as failing, CLEARED as not failing; a row changes only for a higher stateVersion. A malformed event is a
 * [ContractViolation] and writes nothing. No message carries a field value.
 */
class FeedHealthChangedHandler(
    private val tenants: TenantContext,
    private val reader: FeedHealthChangedDataReader,
    private val states: FeedControlStates,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = reader.read(event.dataJson)
        val changed =
            states.record(
                tenants.current(),
                data.feed,
                data.entityRef,
                data.period,
                data.control,
                data.state == FeedHealthState.FAILED,
                data.stateVersion,
            )
        return if (changed) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}
