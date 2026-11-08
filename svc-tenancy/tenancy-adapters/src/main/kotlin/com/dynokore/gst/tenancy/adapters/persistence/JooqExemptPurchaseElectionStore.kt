package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.EXEMPT_PURCHASE_ELECTION
import com.dynokore.gst.tenancy.application.election.ExemptPurchaseElectionRow
import com.dynokore.gst.tenancy.application.election.ExemptPurchaseElectionStore
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext

/** The exempt-purchase elections (jOOQ only, CS-18); forced RLS hides the rows of other tenants and entities are matched by ref. */
class JooqExemptPurchaseElectionStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : ExemptPurchaseElectionStore {
    override fun history(entityRef: String): List<ExemptPurchaseElectionRow> =
        dsl
            .selectFrom(EXEMPT_PURCHASE_ELECTION)
            .where(EXEMPT_PURCHASE_ELECTION.TENANT_ID.eq(tenants.current().value))
            .and(EXEMPT_PURCHASE_ELECTION.ENTITY_REF.eq(entityRef))
            .orderBy(EXEMPT_PURCHASE_ELECTION.EFFECTIVE_FROM, EXEMPT_PURCHASE_ELECTION.ID)
            .fetch()
            .map {
                ExemptPurchaseElectionRow(
                    tenants.current(),
                    it.entityRef.column("entity_ref"),
                    it.effectiveFrom.column("effective_from"),
                    it.taxCodes.column("tax_codes").map { code -> code.column("tax code") },
                    it.basis.column("basis"),
                    it.recordedBy.column("recorded_by"),
                )
            }

    override fun insert(row: ExemptPurchaseElectionRow) {
        dsl
            .insertInto(EXEMPT_PURCHASE_ELECTION)
            .set(EXEMPT_PURCHASE_ELECTION.TENANT_ID, tenants.current().value)
            .set(EXEMPT_PURCHASE_ELECTION.ENTITY_REF, row.entityRef)
            .set(EXEMPT_PURCHASE_ELECTION.EFFECTIVE_FROM, row.effectiveFrom)
            .set(EXEMPT_PURCHASE_ELECTION.TAX_CODES, row.taxCodes.toTypedArray())
            .set(EXEMPT_PURCHASE_ELECTION.BASIS, row.basis)
            .set(EXEMPT_PURCHASE_ELECTION.RECORDED_BY, row.recordedBy)
            .execute()
    }
}
