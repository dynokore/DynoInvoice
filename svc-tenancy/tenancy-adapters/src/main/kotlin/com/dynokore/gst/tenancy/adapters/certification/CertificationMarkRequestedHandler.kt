package com.dynokore.gst.tenancy.adapters.certification

import com.dynokore.gst.tenancy.application.certification.MarkAllStaleForNewTrain
import com.dynokore.gst.tenancy.domain.StaleTrigger
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TransactionRunner
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

/**
 * Idempotent consumer of the internal event CertificationMarkRequested (INV-Q-18156 B). Each tenant is marked in a
 * transaction bound to that tenant, outside the consumer's own transaction (INV-Q-3997: no nested transaction under
 * another binding). A failed mark throws, so the inbox redelivers; an entity already stale is left as it is, so a
 * redelivery or a second delivery changes nothing.
 */
class CertificationMarkRequestedHandler(
    private val markAll: MarkAllStaleForNewTrain,
    private val tx: TransactionRunner,
    private val mapper: ObjectMapper,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        require(event.type == ConsumedCertificationEvents.MARK_REQUESTED) { "unexpected ${event.type}" }
        val trigger =
            try {
                mapper.readTree(event.dataJson)["trigger"]?.takeIf { it.isString }?.asString()
            } catch (e: JacksonException) {
                throw ContractViolation("the payload is not JSON", e)
            }
        if (trigger != StaleTrigger.NEW_TRAIN.name) throw ContractViolation("payload member trigger must be NEW_TRAIN")
        tx.outsideOpenTransaction { markAll.run() }
        return HandleOutcome.APPLIED
    }
}
