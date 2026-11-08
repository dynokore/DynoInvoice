package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.MIGRATION_CUTOVER
import com.dynokore.gst.tenancy.application.migration.MigrationCutover
import com.dynokore.gst.tenancy.application.migration.MigrationCutoverStore
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.ZoneOffset
import java.util.UUID

/** The migration cutovers (jOOQ only, CS-18); forced RLS hides every other tenant's rows. */
class JooqMigrationCutoverStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : MigrationCutoverStore {
    override fun find(entityRef: String): MigrationCutover? =
        dsl
            .select(MIGRATION_CUTOVER.asterisk())
            .from(MIGRATION_CUTOVER)
            .join(ENTITY)
            .on(ENTITY.TENANT_ID.eq(MIGRATION_CUTOVER.TENANT_ID).and(ENTITY.ID.eq(MIGRATION_CUTOVER.ENTITY_ID)))
            .where(MIGRATION_CUTOVER.TENANT_ID.eq(tenants.current().value))
            .and(ENTITY.REF.eq(entityRef))
            .fetchOne()
            ?.let {
                val row = it.into(MIGRATION_CUTOVER)
                MigrationCutover(
                    entityRef,
                    row.cutoverDate.column("cutover_date"),
                    row.priorSolution.column("prior_solution"),
                    row.approvedBy.column("approved_by"),
                    row.evidenceRef,
                    row.recordedAt.column("recorded_at").toInstant(),
                )
            }

    override fun insert(
        entityId: UUID,
        cutover: MigrationCutover,
    ): Boolean =
        dsl
            .insertInto(MIGRATION_CUTOVER)
            .set(MIGRATION_CUTOVER.ID, UUID.randomUUID())
            .set(MIGRATION_CUTOVER.TENANT_ID, tenants.current().value)
            .set(MIGRATION_CUTOVER.ENTITY_ID, entityId)
            .set(MIGRATION_CUTOVER.CUTOVER_DATE, cutover.cutoverDate)
            .set(MIGRATION_CUTOVER.PRIOR_SOLUTION, cutover.priorSolution)
            .set(MIGRATION_CUTOVER.APPROVED_BY, cutover.approvedBy)
            .set(MIGRATION_CUTOVER.EVIDENCE_REF, cutover.evidenceRef)
            .set(MIGRATION_CUTOVER.RECORDED_AT, cutover.recordedAt.atOffset(ZoneOffset.UTC))
            .onConflict(MIGRATION_CUTOVER.TENANT_ID, MIGRATION_CUTOVER.ENTITY_ID)
            .doNothing()
            .execute() == 1
}
