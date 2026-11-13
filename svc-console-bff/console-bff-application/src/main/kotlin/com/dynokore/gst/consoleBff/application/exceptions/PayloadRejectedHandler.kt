package com.dynokore.gst.consoleBff.application.exceptions

import com.dynokore.gst.consoleBff.domain.exceptions.DocumentHeldFacts
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext

/**
 * Opens the exception row of a `PayloadRejected` event (INV-AC-21057), open-only slice.
 * It runs in the consumer's transaction.
 * The raised instant is the envelope `time`; the period reference is the empty string. The tenant-wide and platform
 * entity references name no legal entity and count as absent. A malformed event is a `ContractViolation` raised by
 * the reader (dead letter at once, nothing written).
 */
class PayloadRejectedHandler(
    private val tenants: TenantContext,
    private val reader: PayloadRejectedReader,
    private val record: RecordDocumentHeld,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val envelopeEntityRef = if (event.entity.isTenantWide || event.entity.isPlatform) null else event.entity.value
        val facts = reader.read(event.dataJson, envelopeEntityRef, event.docSequence)
        val held =
            DocumentHeldFacts(
                entityRef = facts.entityRef,
                docUuid = facts.docUuid,
                docsequence = facts.docsequence,
                holdReasons = listOf(facts.code),
                prescribedPeriodRef = "",
                code = facts.code,
                remediationOwner = facts.remediationOwner,
                disposition = facts.disposition,
            )
        val changed = record.record(tenants.current().value.toString(), held, event.time)
        return if (changed) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}
