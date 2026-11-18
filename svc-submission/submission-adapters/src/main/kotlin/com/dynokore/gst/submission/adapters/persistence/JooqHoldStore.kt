package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.ordering.HeldVersion
import com.dynokore.gst.submission.application.ordering.HoldStore
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * [HoldStore] over the generated jOOQ classes of `submission` (INV-REQ-709), in the open tenant transaction. The two
 * state moves are guarded single-row updates with the expected state in the WHERE clause; the held lists join the
 * held rows to the register entry by (tenant_id, doc_uuid), so a version with no entry is not listed.
 */
class JooqHoldStore(
    private val dsl: DSLContext,
) : HoldStore {
    private val s = Submission.SUBMISSION_
    private val e = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY

    override fun holdReady(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): Int = move(tenant, docUuid, version, from = "READY", to = "AWAITING_PRECEDING")

    override fun releaseHeld(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): Int = move(tenant, docUuid, version, from = "AWAITING_PRECEDING", to = "READY")

    override fun heldBy(
        tenant: TenantId,
        precedingDocUuid: String,
    ): List<HeldVersion> = list(tenant, e.PRECEDING_DOC_UUID.eq(precedingDocUuid), null)

    override fun held(
        tenant: TenantId,
        limit: Int,
    ): List<HeldVersion> {
        require(limit >= 1) { "limit must be at least 1: $limit" }
        return list(tenant, DSL.noCondition(), limit)
    }

    private fun move(
        tenant: TenantId,
        docUuid: String,
        version: Int,
        from: String,
        to: String,
    ): Int =
        dsl
            .update(s)
            .set(s.STATE, to)
            .where(s.TENANT_ID.eq(tenant.value))
            .and(s.DOC_UUID.eq(docUuid))
            .and(s.VERSION.eq(version))
            .and(s.STATE.eq(from))
            .execute()

    private fun list(
        tenant: TenantId,
        extra: Condition,
        limit: Int?,
    ): List<HeldVersion> {
        val query =
            dsl
                .select(s.DOC_UUID, s.VERSION, e.PRECEDING_DOC_UUID, e.ENTITY_REF, e.PERIOD_REF)
                .from(s)
                .join(e)
                .on(e.TENANT_ID.eq(s.TENANT_ID))
                .and(e.DOC_UUID.eq(s.DOC_UUID))
                .where(s.TENANT_ID.eq(tenant.value))
                .and(s.STATE.eq("AWAITING_PRECEDING"))
                .and(extra)
                .orderBy(s.DOC_UUID, s.VERSION)
        return (if (limit == null) query else query.limit(limit))
            .fetch()
            .map {
                HeldVersion(
                    checkNotNull(it.get(s.DOC_UUID)),
                    checkNotNull(it.get(s.VERSION)),
                    checkNotNull(it.get(e.PRECEDING_DOC_UUID)),
                    checkNotNull(it.get(e.ENTITY_REF)),
                    checkNotNull(it.get(e.PERIOD_REF)),
                )
            }
    }
}
