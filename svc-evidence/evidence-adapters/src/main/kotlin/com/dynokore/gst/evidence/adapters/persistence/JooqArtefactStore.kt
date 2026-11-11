package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.EvidenceArtefact.Companion.EVIDENCE_ARTEFACT
import com.dynokore.gst.evidence.application.artefact.ArtefactStore
import com.dynokore.platform.serviceruntime.events.EvidenceArtefact
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.time.Instant
import java.util.UUID

/** `evidence_artefact` over the service's tenant-bound [DSLContext] (jOOQ only, STK §3). */
class JooqArtefactStore(
    private val dsl: DSLContext,
) : ArtefactStore {
    override fun insert(
        tenant: TenantId,
        entityRef: String?,
        artefact: EvidenceArtefact,
        eventId: String,
        recordedAt: Instant,
    ): Boolean =
        dsl
            .insertInto(EVIDENCE_ARTEFACT)
            .set(EVIDENCE_ARTEFACT.ID, UUID.randomUUID())
            .set(EVIDENCE_ARTEFACT.TENANT_ID, tenant.value)
            .set(EVIDENCE_ARTEFACT.ENTITY_REF, entityRef)
            .set(EVIDENCE_ARTEFACT.ARTEFACT_TYPE, artefact.type.value)
            .set(EVIDENCE_ARTEFACT.REFERENCE, artefact.reference)
            .set(EVIDENCE_ARTEFACT.HASH, artefact.hash)
            .set(EVIDENCE_ARTEFACT.EVENT_ID, eventId)
            .set(EVIDENCE_ARTEFACT.RECORDED_AT, recordedAt.utc())
            .onConflict(
                EVIDENCE_ARTEFACT.TENANT_ID,
                EVIDENCE_ARTEFACT.ARTEFACT_TYPE,
                EVIDENCE_ARTEFACT.REFERENCE,
                EVIDENCE_ARTEFACT.HASH,
            ).doNothing()
            .execute() == 1
}
