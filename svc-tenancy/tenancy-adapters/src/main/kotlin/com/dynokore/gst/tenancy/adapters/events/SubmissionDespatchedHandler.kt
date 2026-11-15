package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.adapters.persistence.JooqDespatchFactStore
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome

/**
 * The consumer of `gst.submission.submission-despatched.v1` (INV-Q-16675 option A, INV-AC-16774): keeps the first fact
 * per (tenant, entity) and, for an event with a tenant and no entity, the tenant's unattributed marker. It reads
 * envelope attributes only (`tenantid`, `entityref`, the time and the Doc UUID); no payload member is read. An event
 * without a tenant is a [ContractViolation] (dead letter). It publishes no event and writes nothing else. A repeat of
 * a key, or of an event id, changes nothing.
 */
class SubmissionDespatchedHandler(
    private val facts: JooqDespatchFactStore,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        if (event.tenant.isPlatform) throw ContractViolation("the envelope has no tenantid")
        if (event.type != TenancyEventCatalogue.SUBMISSION_DESPATCHED_TYPE) return HandleOutcome.IGNORED
        val docUuid = event.docUuid?.value
        val inserted =
            if (event.entity.isTenantWide) {
                facts.keepUnattributedMarker(event.tenant, event.time, docUuid)
            } else {
                facts.keepEntityFact(event.tenant, event.entity.value, event.time, docUuid)
            }
        return if (inserted) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}
