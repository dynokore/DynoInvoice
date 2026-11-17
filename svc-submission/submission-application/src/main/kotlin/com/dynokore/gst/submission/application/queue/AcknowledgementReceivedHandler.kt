package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.transitions.Event
import com.dynokore.gst.submission.domain.transitions.Signal
import com.dynokore.gst.submission.domain.transitions.VersionRecord
import com.dynokore.gst.submission.domain.transitions.VersionTransitions
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import java.time.Clock

/**
 * Consumes `AcknowledgementReceived` (INV-REQ-797, INV-REQ-543). It runs in the consumer's transaction, so the inbox
 * row, the state change, the acknowledgement columns and the outbox event commit or roll back together
 * (`EventHandler`). Every version row of the document is locked first (`lockVersions`), then the decision is made by
 * [VersionTransitions.decide]; only after it are writes made. A refusal or an anomaly is IGNORED with no write; an
 * outcome other than ACCEPTED throws [UnsupportedAcknowledgementOutcome] (dead letter, never dropped).
 */
@Suppress("LongParameterList") // the constructor shape is ruled (INV-T-24274 design)
class AcknowledgementReceivedHandler(
    private val tenants: TenantContext,
    private val reader: AcknowledgementDataReader,
    private val inFlight: InFlightStore,
    private val store: SubmissionStore,
    private val recorder: AcknowledgementRecorder,
    private val publisher: AcknowledgedPublisher,
    private val clock: Clock,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = reader.read(event.dataJson)
        if (data.outcome != AcknowledgementReceivedData.OUTCOME_ACCEPTED) {
            throw UnsupportedAcknowledgementOutcome(data.outcome)
        }
        val tenant = tenants.current()
        val locked = inFlight.lockVersions(tenant, data.docUuid)
        val records =
            locked.map {
                val id =
                    if (it.state == LifecycleState.ACKNOWLEDGED) {
                        store.lockForUpdate(tenant, data.docUuid, it.version)?.acknowledgementId
                    } else {
                        null
                    }
                VersionRecord(it.version, it.state, id)
            }
        val decision =
            VersionTransitions.decide(records, Event.AcknowledgementReceived(data.version, data.acknowledgementId))
        val record = decision.signals.filterIsInstance<Signal.RecordAcknowledgement>().singleOrNull()
        val rejected = decision.signals.any { it is Signal.Refused || it is Signal.Anomaly }
        if (record == null || rejected) return HandleOutcome.IGNORED
        for (transition in decision.transitions) {
            val row = store.lockForUpdate(tenant, data.docUuid, transition.version)
            check(row != null && row.state == transition.from) {
                "version ${transition.version} of ${data.docUuid} is not in ${transition.from}"
            }
        }
        for (transition in decision.transitions) {
            store.setState(tenant, data.docUuid, transition.version, transition.to)
        }
        val now = clock.instant()
        recorder.record(tenant, data.docUuid, record.version, record.acknowledgementId, now)
        publisher.stage(AcknowledgedFact(data.docUuid, record.version, record.acknowledgementId, now))
        return HandleOutcome.APPLIED
    }
}
