package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.RENDITION_ATTESTATION
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.RENDITION_FINDING_SEQUENCE
import com.dynokore.gst.tenancy.application.rendition.RenditionAttestation
import com.dynokore.gst.tenancy.application.rendition.RenditionAttestationStore
import com.dynokore.gst.tenancy.application.rendition.RenditionEntities
import com.dynokore.gst.tenancy.domain.AttestationState
import com.dynokore.gst.tenancy.domain.Renderer
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The attestation rows and the last finding sequence per entity of the bound tenant (jOOQ only, CS-18). Forced
 * row-level security shows only the bound tenant's rows; [clock] stamps the date of attestation.
 */
class JooqRenditionStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: () -> Instant,
) : RenditionAttestationStore,
    RenditionEntities {
    override fun exists(entityRef: String): Boolean = entityId(entityRef) != null

    override fun put(attestation: RenditionAttestation) {
        val entity = checkNotNull(entityId(attestation.entityRef)) { "no such entity" }
        val at = OffsetDateTime.ofInstant(clock(), ZoneOffset.UTC)
        dsl
            .insertInto(RENDITION_ATTESTATION)
            .set(RENDITION_ATTESTATION.TENANT_ID, tenants.current().value)
            .set(RENDITION_ATTESTATION.ENTITY_ID, entity)
            .set(RENDITION_ATTESTATION.DOCUMENT_CLASS, attestation.documentClass)
            .set(RENDITION_ATTESTATION.RENDERER, attestation.renderer.name)
            .set(RENDITION_ATTESTATION.ATTESTED_BY, attestation.attestedBy)
            .set(RENDITION_ATTESTATION.ATTESTED_AT, at)
            .set(RENDITION_ATTESTATION.STATE, attestation.state.name)
            .onConflict(
                RENDITION_ATTESTATION.TENANT_ID,
                RENDITION_ATTESTATION.ENTITY_ID,
                RENDITION_ATTESTATION.DOCUMENT_CLASS,
            ).doUpdate()
            .set(RENDITION_ATTESTATION.RENDERER, attestation.renderer.name)
            .set(RENDITION_ATTESTATION.ATTESTED_BY, attestation.attestedBy)
            .set(RENDITION_ATTESTATION.ATTESTED_AT, at)
            .set(RENDITION_ATTESTATION.STATE, attestation.state.name)
            .execute()
    }

    override fun find(
        entityRef: String,
        documentClass: String,
    ): RenditionAttestation? =
        dsl
            .select(
                RENDITION_ATTESTATION.RENDERER,
                RENDITION_ATTESTATION.ATTESTED_BY,
                RENDITION_ATTESTATION.STATE,
            ).from(RENDITION_ATTESTATION)
            .join(ENTITY)
            .on(
                ENTITY.TENANT_ID
                    .eq(RENDITION_ATTESTATION.TENANT_ID)
                    .and(ENTITY.ID.eq(RENDITION_ATTESTATION.ENTITY_ID)),
            ).where(ENTITY.REF.eq(entityRef).and(RENDITION_ATTESTATION.DOCUMENT_CLASS.eq(documentClass)))
            .fetchOne()
            ?.let {
                RenditionAttestation(
                    entityRef,
                    documentClass,
                    Renderer.valueOf(it[RENDITION_ATTESTATION.RENDERER].column("renderer")),
                    it[RENDITION_ATTESTATION.ATTESTED_BY].column("attested_by"),
                    AttestationState.valueOf(it[RENDITION_ATTESTATION.STATE].column("state")),
                )
            }

    override fun setStateForEntity(
        entityRef: String,
        state: AttestationState,
    ): Int {
        val entity = entityId(entityRef) ?: return 0
        return dsl
            .update(RENDITION_ATTESTATION)
            .set(RENDITION_ATTESTATION.STATE, state.name)
            .where(RENDITION_ATTESTATION.ENTITY_ID.eq(entity))
            .execute()
    }

    override fun lastSequence(entityRef: String): Long? {
        val entity = entityId(entityRef) ?: return null
        return dsl
            .select(RENDITION_FINDING_SEQUENCE.LAST_SEQUENCE)
            .from(RENDITION_FINDING_SEQUENCE)
            .where(RENDITION_FINDING_SEQUENCE.ENTITY_ID.eq(entity))
            .fetchOne()
            ?.get(RENDITION_FINDING_SEQUENCE.LAST_SEQUENCE)
    }

    override fun storeSequence(
        entityRef: String,
        sequence: Long,
    ) {
        val entity = entityId(entityRef) ?: return
        dsl
            .insertInto(RENDITION_FINDING_SEQUENCE)
            .set(RENDITION_FINDING_SEQUENCE.TENANT_ID, tenants.current().value)
            .set(RENDITION_FINDING_SEQUENCE.ENTITY_ID, entity)
            .set(RENDITION_FINDING_SEQUENCE.LAST_SEQUENCE, sequence)
            .onConflict(RENDITION_FINDING_SEQUENCE.TENANT_ID, RENDITION_FINDING_SEQUENCE.ENTITY_ID)
            .doUpdate()
            .set(RENDITION_FINDING_SEQUENCE.LAST_SEQUENCE, sequence)
            .execute()
    }

    private fun entityId(entityRef: String): UUID? =
        dsl
            .select(ENTITY.ID)
            .from(ENTITY)
            .where(ENTITY.REF.eq(entityRef))
            .fetchOne()
            ?.get(ENTITY.ID)
}
