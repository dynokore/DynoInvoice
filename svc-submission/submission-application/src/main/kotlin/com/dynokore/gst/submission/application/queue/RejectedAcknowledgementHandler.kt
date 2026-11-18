package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.transitions.Event
import com.dynokore.gst.submission.domain.transitions.RejectionSource
import com.dynokore.gst.submission.domain.transitions.Signal
import com.dynokore.gst.submission.domain.transitions.Transition
import com.dynokore.gst.submission.domain.transitions.VersionRecord
import com.dynokore.gst.submission.domain.transitions.VersionTransitions
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext

/**
 * Consumes `AcknowledgementReceived` with outcome REJECTED (INV-REQ-797, INV-REQ-940). It runs in the consumer's
 * transaction, so the inbox row, the state, the failure code and the outbox row commit or roll back together. The
 * decision is [VersionTransitions.decide]; writes follow it. An anomaly (a repeat, an acknowledged version or any other
 * state) is IGNORED with no write. The raised SUB-004 signal is not acted on here.
 */
@Suppress("LongParameterList") // the constructor shape is ruled (INV-T-24284 design)
class RejectedAcknowledgementHandler(
    private val tenants: TenantContext,
    private val reader: RejectedAcknowledgementReader,
    private val inFlight: InFlightStore,
    private val store: SubmissionStore,
    private val attempts: LatestAttempt,
    private val codes: FailureCodes,
    private val catalogue: ErrorCatalogue,
    private val signals: FailureSignals,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = reader.read(event.dataJson)
        val code =
            requireNotNull(data.reasons.firstOrNull()?.code) {
                "rejection of ${data.docUuid} version ${data.version} has no reason"
            }
        val tenant = tenants.current()
        val records = inFlight.lockVersions(tenant, data.docUuid).map { VersionRecord(it.version, it.state) }
        val decision =
            VersionTransitions.decide(records, Event.Rejected(data.version, RejectionSource.IRAS, data.reasons))
        if (decision.signals.any { it is Signal.Anomaly }) return HandleOutcome.IGNORED
        val transition = decision.transitions.singleOrNull()
        check(transition != null && transition.version == data.version && transition.to == LifecycleState.REJECTED) {
            "rejection of ${data.docUuid} version ${data.version}: unexpected decision $decision"
        }
        apply(transition, data, code)
        return HandleOutcome.APPLIED
    }

    private fun apply(
        transition: Transition,
        data: RejectedAcknowledgementData,
        code: String,
    ) {
        val tenant = tenants.current()
        val row = store.lockForUpdate(tenant, data.docUuid, data.version)
        check(row != null && row.state == transition.from) {
            "version ${data.version} of ${data.docUuid} is not in ${transition.from}"
        }
        store.setState(tenant, data.docUuid, data.version, transition.to)
        attempts.numberOf(tenant, data.docUuid, data.version)?.let {
            codes.record(tenant, data.docUuid, data.version, it, code)
        }
        signals.stage(
            SubmissionFailure.of(data.docUuid, data.version, LifecycleState.REJECTED, code, catalogue.entryOf(code)),
        )
    }
}
