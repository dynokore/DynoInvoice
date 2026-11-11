package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.RENDITION_ATTESTATION
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.RENDITION_ATTESTATION_HISTORY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.RENDITION_FINDING_SEQUENCE
import com.dynokore.gst.tenancy.application.rendition.RenditionAttestation
import com.dynokore.gst.tenancy.application.rendition.RenditionAttestationHistory
import com.dynokore.gst.tenancy.application.rendition.RenditionAttestationRecord
import com.dynokore.gst.tenancy.application.rendition.RenditionAttestationStore
import com.dynokore.gst.tenancy.application.rendition.RenditionEntities
import com.dynokore.gst.tenancy.application.rendition.RenditionRecordKind
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
 * row-level security shows only the bound tenant's rows; [clock] stamps the date of attestation. Every attestation and
 * every state change also adds a row to the append-only history in the caller's transaction (INV-REQ-1036).
 */
class JooqRenditionStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: () -> Instant,
) : RenditionAttestationStore,
    RenditionEntities,
    RenditionAttestationHistory {
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
        dsl
            .insertInto(RENDITION_ATTESTATION_HISTORY)
            .set(RENDITION_ATTESTATION_HISTORY.TENANT_ID, tenants.current().value)
            .set(RENDITION_ATTESTATION_HISTORY.ENTITY_ID, entity)
            .set(RENDITION_ATTESTATION_HISTORY.DOCUMENT_CLASS, attestation.documentClass)
            .set(RENDITION_ATTESTATION_HISTORY.KIND, RenditionRecordKind.ATTESTED.name)
            .set(RENDITION_ATTESTATION_HISTORY.RENDERER, attestation.renderer.name)
            .set(RENDITION_ATTESTATION_HISTORY.ATTESTED_BY, attestation.attestedBy)
            .set(RENDITION_ATTESTATION_HISTORY.ATTESTED_AT, at)
            .set(RENDITION_ATTESTATION_HISTORY.STATE, attestation.state.name)
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
        // one STATE_CHANGED row per class whose state differs, from the current row before it is updated
        dsl
            .select(
                RENDITION_ATTESTATION.DOCUMENT_CLASS,
                RENDITION_ATTESTATION.RENDERER,
                RENDITION_ATTESTATION.ATTESTED_BY,
                RENDITION_ATTESTATION.ATTESTED_AT,
            ).from(RENDITION_ATTESTATION)
            .where(RENDITION_ATTESTATION.ENTITY_ID.eq(entity).and(RENDITION_ATTESTATION.STATE.ne(state.name)))
            .fetch()
            .forEach {
                dsl
                    .insertInto(RENDITION_ATTESTATION_HISTORY)
                    .set(RENDITION_ATTESTATION_HISTORY.TENANT_ID, tenants.current().value)
                    .set(RENDITION_ATTESTATION_HISTORY.ENTITY_ID, entity)
                    .set(RENDITION_ATTESTATION_HISTORY.DOCUMENT_CLASS, it[RENDITION_ATTESTATION.DOCUMENT_CLASS])
                    .set(RENDITION_ATTESTATION_HISTORY.KIND, RenditionRecordKind.STATE_CHANGED.name)
                    .set(RENDITION_ATTESTATION_HISTORY.RENDERER, it[RENDITION_ATTESTATION.RENDERER])
                    .set(RENDITION_ATTESTATION_HISTORY.ATTESTED_BY, it[RENDITION_ATTESTATION.ATTESTED_BY])
                    .set(RENDITION_ATTESTATION_HISTORY.ATTESTED_AT, it[RENDITION_ATTESTATION.ATTESTED_AT])
                    .set(RENDITION_ATTESTATION_HISTORY.STATE, state.name)
                    .execute()
            }
        return dsl
            .update(RENDITION_ATTESTATION)
            .set(RENDITION_ATTESTATION.STATE, state.name)
            .where(RENDITION_ATTESTATION.ENTITY_ID.eq(entity))
            .execute()
    }

    override fun list(
        entityRef: String,
        documentClass: String,
    ): List<RenditionAttestationRecord> =
        dsl
            .select(
                RENDITION_ATTESTATION_HISTORY.SEQ,
                RENDITION_ATTESTATION_HISTORY.KIND,
                RENDITION_ATTESTATION_HISTORY.RENDERER,
                RENDITION_ATTESTATION_HISTORY.ATTESTED_BY,
                RENDITION_ATTESTATION_HISTORY.ATTESTED_AT,
                RENDITION_ATTESTATION_HISTORY.STATE,
            ).from(RENDITION_ATTESTATION_HISTORY)
            .join(ENTITY)
            .on(
                ENTITY.TENANT_ID
                    .eq(RENDITION_ATTESTATION_HISTORY.TENANT_ID)
                    .and(ENTITY.ID.eq(RENDITION_ATTESTATION_HISTORY.ENTITY_ID)),
            ).where(
                ENTITY.REF
                    .eq(entityRef)
                    .and(RENDITION_ATTESTATION_HISTORY.DOCUMENT_CLASS.eq(documentClass)),
            ).orderBy(RENDITION_ATTESTATION_HISTORY.SEQ)
            .fetch()
            .map {
                RenditionAttestationRecord(
                    it[RENDITION_ATTESTATION_HISTORY.SEQ].column("seq"),
                    RenditionRecordKind.valueOf(it[RENDITION_ATTESTATION_HISTORY.KIND].column("kind")),
                    Renderer.valueOf(it[RENDITION_ATTESTATION_HISTORY.RENDERER].column("renderer")),
                    it[RENDITION_ATTESTATION_HISTORY.ATTESTED_BY].column("attested_by"),
                    it[RENDITION_ATTESTATION_HISTORY.ATTESTED_AT].column("attested_at").toInstant(),
                    AttestationState.valueOf(it[RENDITION_ATTESTATION_HISTORY.STATE].column("state")),
                )
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
