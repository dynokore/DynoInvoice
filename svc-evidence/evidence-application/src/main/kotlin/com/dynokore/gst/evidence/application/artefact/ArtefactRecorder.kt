package com.dynokore.gst.evidence.application.artefact

import com.dynokore.platform.serviceruntime.events.EvidenceArtefact
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant

/** The append-only store of recorded artefacts (INV-REQ-1036 behavior 2). */
interface ArtefactStore {
    /** True when a row was stored; false when the (tenant, type, reference, hash) key already existed. */
    fun insert(
        tenant: TenantId,
        entityRef: String?,
        artefact: EvidenceArtefact,
        eventId: String,
        recordedAt: Instant,
    ): Boolean
}

/**
 * Stores the artefact of an `EvidenceArtefactRecorded` event, once per (tenant, artefact type, reference, hash): a
 * repeat or a second producer changes nothing. A type outside `EvidenceArtefactType.known` is stored as received.
 * Runs in the consumer's tenant transaction.
 */
class ArtefactRecorder(
    private val store: ArtefactStore,
) {
    /** True when a row was stored, false when the artefact was already held. */
    fun record(
        tenant: TenantId,
        entityRef: String?,
        artefact: EvidenceArtefact,
        eventId: String,
        at: Instant,
    ): Boolean = store.insert(tenant, entityRef, artefact, eventId, at)
}
