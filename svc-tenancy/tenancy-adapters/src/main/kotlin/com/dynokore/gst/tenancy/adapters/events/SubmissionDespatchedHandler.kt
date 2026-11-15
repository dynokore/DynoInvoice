package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.adapters.persistence.JooqDespatchFactStore
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome

/**
 * The consumer of `gst.submission.submission-despatched.v1` (INV-Q-16675 option A, INV-AC-16774): keeps the first fact
 * per (tenant, entity). It reads envelope attributes only (`tenantid`, `entityref`, the time and the Doc UUID); no
 * payload member is read. An event without `tenantid` or `entityref` never reaches it: the shared envelope reader
 * dead-letters it, and there is no branch or marker for it (INV-REQ-1162). It publishes no event and writes nothing
 * else. A repeat of a key, or of an event id, changes nothing.
 */
class SubmissionDespatchedHandler(
    private val facts: JooqDespatchFactStore,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        if (event.tenant.isPlatform) throw ContractViolation("the envelope has no tenantid")
        if (event.type != TenancyEventCatalogue.SUBMISSION_DESPATCHED_TYPE) return HandleOutcome.IGNORED
        val docUuid = event.docUuid?.value
        val inserted = facts.keepEntityFact(event.tenant, event.entity.value, event.time, docUuid)
        return if (inserted) HandleOutcome.APPLIED else HandleOutcome.IGNORED
    }
}
