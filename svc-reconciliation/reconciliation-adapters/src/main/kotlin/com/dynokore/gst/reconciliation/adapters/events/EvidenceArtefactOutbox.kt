package com.dynokore.gst.reconciliation.adapters.events

import com.dynokore.gst.reconciliation.application.workpaper.ArtefactRecordedPublisher
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.events.EvidenceArtefact
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactRecorder
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactType
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantId
import java.util.UUID

/**
 * INV-T-24187, INV-AC-24183: publishes `EvidenceArtefactRecorded` for a sealed work-paper through the service-runtime
 * recorder, in the caller's transaction (INV-ADR-283). The payload is the three fields of the artefact and no other.
 */
class EvidenceArtefactOutbox(
    private val recorder: EvidenceArtefactRecorder,
) : ArtefactRecordedPublisher {
    override fun publish(
        tenant: String,
        entityRef: String,
        artefactType: String,
        reference: String,
        sha256Hex: String,
    ) {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        recorder.record(
            EvidenceArtefact(EvidenceArtefactType(artefactType), reference, sha256Hex),
            EventContext(
                tenant = TenantId(UUID.fromString(tenant)),
                entity = EntityRef(entityRef),
                docUuid = null,
                transactionDate = null,
                aggregateKey = reference,
                aggregateVersion = null,
                correlation = CorrelationId(correlation),
                causation = CausationId(causation),
            ),
        )
    }
}
