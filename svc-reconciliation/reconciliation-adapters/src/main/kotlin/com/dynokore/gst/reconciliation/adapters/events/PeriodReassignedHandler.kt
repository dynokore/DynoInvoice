package com.dynokore.gst.reconciliation.adapters.events

import com.dynokore.gst.reconciliation.application.run.RunReadModelWriter
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome

/**
 * The consumer of `gst.canonical.period-reassigned.v1` for the run read model (INV-REQ-1206): moves the period of the
 * document to `newPrescribedPeriodRef` when the `docsequence` is newer (the writer decides, INV-REQ-1221). It runs
 * in the inbox consumer's transaction and opens none. A payload without a required field is a ContractViolation.
 */
class PeriodReassignedHandler(
    private val writer: RunReadModelWriter,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = PayloadReading.parse(event.dataJson)
        val applied =
            writer.reassignPeriod(
                tenant = event.tenant.toString(),
                entityRef = event.entity.value,
                docUuid = PayloadReading.uuid(data, "docUuid"),
                docsequence = PayloadReading.positiveLong(data, "docsequence"),
                newPeriodRef = PayloadReading.text(data, "newPrescribedPeriodRef"),
            )
        return if (applied) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}
