package com.dynokore.gst.masterdata.adapters.events

import com.dynokore.gst.masterdata.application.imports.ImportEvidence
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.events.EvidenceArtefact
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactRecorder
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactType
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import java.util.UUID

/**
 * INV-REQ-833, INV-AC-9603: publishes the finalised import record as `EvidenceArtefactRecorded` (type `IMPORT`,
 * reference the import id, hash the SHA-256 of the record bytes) through the service-runtime recorder, in the
 * transaction that finalises the record.
 */
class ImportEvidenceOutbox(
    private val recorder: EvidenceArtefactRecorder,
    private val tenants: TenantContext,
) : ImportEvidence {
    override fun recorded(
        entity: String,
        importId: String,
        hash: String,
    ) {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        recorder.record(
            EvidenceArtefact(EvidenceArtefactType.IMPORT, importId, hash),
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(entity),
                docUuid = null,
                transactionDate = null,
                aggregateKey = importId,
                aggregateVersion = null,
                correlation = CorrelationId(correlation),
                causation = CausationId(causation),
            ),
        )
    }
}
