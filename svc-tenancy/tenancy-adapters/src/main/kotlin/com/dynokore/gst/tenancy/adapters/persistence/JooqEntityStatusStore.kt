package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY_STATUS
import com.dynokore.gst.tenancy.application.status.EntityStatusEntry
import com.dynokore.gst.tenancy.application.status.EntityStatusStore
import com.dynokore.gst.tenancy.domain.ExcludedBusinessStatus
import com.dynokore.gst.tenancy.domain.ObligationStartBasis
import com.dynokore.gst.tenancy.domain.StatusKind
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The status history of the bound tenant's entities (jOOQ only, CS-18); forced RLS hides every other tenant's rows. */
class JooqEntityStatusStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : EntityStatusStore {
    override fun history(entityId: UUID): List<EntityStatusEntry> =
        dsl
            .selectFrom(ENTITY_STATUS)
            .where(ENTITY_STATUS.ENTITY_ID.eq(entityId))
            .orderBy(ENTITY_STATUS.EFFECTIVE_FROM, ENTITY_STATUS.RECORDED_AT)
            .fetch()
            .map {
                EntityStatusEntry(
                    StatusKind.valueOf(it.statusKind.column("status_kind")),
                    it.effectiveFrom.column("effective_from"),
                    it.partiallyExempt,
                    it.excludedBusinessStatus?.let(ExcludedBusinessStatus::valueOf),
                    it.basis,
                    it.evidenceRef,
                    it.obligationStartDate,
                    it.obligationStartBasis?.let(ObligationStartBasis::valueOf),
                    it.irasReference,
                    it.obligationStartOverride,
                    it.registrationStartDate,
                )
            }

    override fun insert(
        entityId: UUID,
        entry: EntityStatusEntry,
    ) {
        dsl
            .insertInto(ENTITY_STATUS)
            .set(ENTITY_STATUS.ID, UUID.randomUUID())
            .set(ENTITY_STATUS.TENANT_ID, tenants.current().value)
            .set(ENTITY_STATUS.ENTITY_ID, entityId)
            .set(ENTITY_STATUS.STATUS_KIND, entry.kind.name)
            .set(ENTITY_STATUS.EFFECTIVE_FROM, entry.effectiveFrom)
            .set(ENTITY_STATUS.PARTIALLY_EXEMPT, entry.partiallyExempt)
            .set(ENTITY_STATUS.EXCLUDED_BUSINESS_STATUS, entry.excludedBusinessStatus?.name)
            .set(ENTITY_STATUS.BASIS, entry.basis)
            .set(ENTITY_STATUS.EVIDENCE_REF, entry.evidenceRef)
            .set(ENTITY_STATUS.OBLIGATION_START_DATE, entry.obligationStartDate)
            .set(ENTITY_STATUS.OBLIGATION_START_BASIS, entry.obligationStartBasis?.name)
            .set(ENTITY_STATUS.IRAS_REFERENCE, entry.irasReference)
            .set(ENTITY_STATUS.OBLIGATION_START_OVERRIDE, entry.obligationStartOverride)
            .set(ENTITY_STATUS.REGISTRATION_START_DATE, entry.registrationStartDate)
            .set(ENTITY_STATUS.RECORDED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
    }
}
