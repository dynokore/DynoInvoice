package com.dynokore.gst.consoleBff.adapters.exceptions

import com.dynokore.gst.consoleBff.adapters.persistence.jooq.tables.references.EXCEPTION
import com.dynokore.gst.consoleBff.application.exceptions.ExceptionStore
import com.dynokore.gst.consoleBff.domain.badge.ItemState
import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionRow
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.ZoneOffset
import java.util.UUID

/**
 * `console_bff.exception` through the jOOQ DSL and the generated table class (TPL-11). Isolation is the forced row
 * level security of the table under the caller's signed tenant binding; every statement also names the tenant, so a
 * wrong tenant id is a no-row result rather than a leak (defence in depth, NIST SP 800-53 AC-3).
 */
class JooqExceptionStore(
    private val dsl: DSLContext,
) : ExceptionStore {
    /** Idempotent on (tenant, exceptionId); a stored OVERRIDDEN row is a human decision and is kept. */
    override fun upsert(rows: List<ExceptionRow>) {
        rows.forEach { row ->
            dsl
                .insertInto(EXCEPTION)
                .set(EXCEPTION.TENANT_ID, UUID.fromString(row.tenantId))
                .set(EXCEPTION.EXCEPTION_ID, row.exceptionId)
                .set(EXCEPTION.ENTITY_REF, row.entityRef)
                .set(EXCEPTION.DOC_UUID, row.docUuid)
                .set(EXCEPTION.CODE, row.code)
                .set(EXCEPTION.STATE, row.state.name)
                .set(EXCEPTION.RAISED_AT, row.raisedAt.atOffset(ZoneOffset.UTC))
                .set(EXCEPTION.REMEDIATION_OWNER, row.remediationOwner)
                .set(EXCEPTION.DISPOSITION, row.disposition)
                .set(EXCEPTION.PERIOD_REF, row.periodRef)
                .set(EXCEPTION.DOCSEQUENCE, row.docsequence)
                .onConflict(EXCEPTION.TENANT_ID, EXCEPTION.EXCEPTION_ID)
                .doUpdate()
                .set(EXCEPTION.ENTITY_REF, row.entityRef)
                .set(EXCEPTION.DOC_UUID, row.docUuid)
                .set(EXCEPTION.CODE, row.code)
                .set(EXCEPTION.STATE, row.state.name)
                .set(EXCEPTION.RAISED_AT, row.raisedAt.atOffset(ZoneOffset.UTC))
                .set(EXCEPTION.REMEDIATION_OWNER, row.remediationOwner)
                .set(EXCEPTION.DISPOSITION, row.disposition)
                .set(EXCEPTION.PERIOD_REF, row.periodRef)
                .set(EXCEPTION.DOCSEQUENCE, row.docsequence)
                .where(EXCEPTION.STATE.ne(ItemState.OVERRIDDEN.name))
                .execute()
        }
    }

    override fun openRowsOfDocument(
        tenantId: String,
        docUuid: String,
    ): List<ExceptionRow> = read(tenantId, docUuid, onlyOpen = true)

    override fun rowsOfDocument(
        tenantId: String,
        docUuid: String,
    ): List<ExceptionRow> = read(tenantId, docUuid, onlyOpen = false)

    private fun read(
        tenantId: String,
        docUuid: String,
        onlyOpen: Boolean,
    ): List<ExceptionRow> =
        dsl
            .selectFrom(EXCEPTION)
            .where(EXCEPTION.TENANT_ID.eq(UUID.fromString(tenantId)))
            .and(EXCEPTION.DOC_UUID.eq(docUuid))
            .and(if (onlyOpen) EXCEPTION.STATE.eq(ItemState.OPEN.name) else DSL.noCondition())
            .orderBy(EXCEPTION.EXCEPTION_ID)
            .fetch()
            .map { it.toRow() }

    private fun Record.toRow() =
        ExceptionRow(
            tenantId = req(EXCEPTION.TENANT_ID).toString(),
            exceptionId = req(EXCEPTION.EXCEPTION_ID),
            entityRef = req(EXCEPTION.ENTITY_REF),
            docUuid = req(EXCEPTION.DOC_UUID),
            code = req(EXCEPTION.CODE),
            remediationOwner = req(EXCEPTION.REMEDIATION_OWNER),
            disposition = req(EXCEPTION.DISPOSITION),
            state = ItemState.valueOf(req(EXCEPTION.STATE)),
            raisedAt = req(EXCEPTION.RAISED_AT).toInstant(),
            periodRef = req(EXCEPTION.PERIOD_REF),
            docsequence = req(EXCEPTION.DOCSEQUENCE),
        )

    private fun <T : Any> Record.req(field: Field<T?>): T = checkNotNull(get(field)) { "${field.name} is null" }
}
