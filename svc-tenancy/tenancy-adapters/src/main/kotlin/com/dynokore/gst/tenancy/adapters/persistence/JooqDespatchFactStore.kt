package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.DESPATCH_FACT
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The replica of SubmissionDespatched facts (INV-REQ-1243, INV-AC-16774; jOOQ only, CS-18): one row per tenant and
 * entity ref, and one unattributed marker per tenant. Insert-only; the first fact of a key stays.
 */
class JooqDespatchFactStore(
    private val dsl: DSLContext,
) {
    /** Keeps the fact of ([tenant], [entityRef]) when none exists; true when a row was inserted. */
    fun keepEntityFact(
        tenant: TenantId,
        entityRef: String,
        despatchedAt: Instant,
        docUuid: UUID?,
    ): Boolean = insert(tenant, ENTITY, entityRef, despatchedAt, docUuid)

    /** Keeps the unattributed marker of [tenant] when none exists; true when a row was inserted. */
    fun keepUnattributedMarker(
        tenant: TenantId,
        despatchedAt: Instant,
        docUuid: UUID?,
    ): Boolean = insert(tenant, UNATTRIBUTED, null, despatchedAt, docUuid)

    /**
     * True when [tenant] has the unattributed marker, or a fact whose entity ref equals [entityRef] (exact equality,
     * never a prefix). Every other tenant's row is out of reach by condition and by forced row-level security.
     */
    fun hasFactFor(
        tenant: TenantId,
        entityRef: String,
    ): Boolean =
        dsl.fetchExists(
            DESPATCH_FACT,
            DESPATCH_FACT.TENANT_ID
                .eq(tenant.value)
                .and(
                    DESPATCH_FACT.ROW_KIND
                        .eq(UNATTRIBUTED)
                        .or(DESPATCH_FACT.ROW_KIND.eq(ENTITY).and(DESPATCH_FACT.ENTITY_REF.eq(entityRef))),
                ),
        )

    private fun insert(
        tenant: TenantId,
        kind: String,
        entityRef: String?,
        despatchedAt: Instant,
        docUuid: UUID?,
    ): Boolean =
        dsl
            .insertInto(DESPATCH_FACT)
            .set(DESPATCH_FACT.TENANT_ID, tenant.value)
            .set(DESPATCH_FACT.ROW_KIND, kind)
            .set(DESPATCH_FACT.ENTITY_REF, entityRef)
            .set(DESPATCH_FACT.FIRST_DESPATCHED_AT, OffsetDateTime.ofInstant(despatchedAt, ZoneOffset.UTC))
            .set(DESPATCH_FACT.DOC_UUID, docUuid)
            .onConflictDoNothing()
            .execute() == 1

    companion object {
        const val ENTITY = "ENTITY"
        const val UNATTRIBUTED = "UNATTRIBUTED"
    }
}
