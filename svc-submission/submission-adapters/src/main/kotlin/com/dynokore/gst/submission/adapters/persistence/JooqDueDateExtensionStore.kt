package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.DueDateExtension as ExtensionTable
import com.dynokore.gst.submission.application.extension.DueDateExtension
import com.dynokore.gst.submission.application.extension.DueDateExtensions
import com.dynokore.gst.submission.application.extension.ExtensionScopeKind
import com.dynokore.gst.submission.application.extension.ExtensionTarget
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext

/**
 * [DueDateExtensions] over `submission.due_date_extension`: `insert ... on conflict (tenant_id, source_event_id) do
 * nothing` (one row: stored), and a select in order of recording. No update or delete exists, and the runtime role
 * holds none; forced row level security applies (CS-19). Values are never logged.
 */
class JooqDueDateExtensionStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : DueDateExtensions {
    private val t = ExtensionTable.DUE_DATE_EXTENSION

    override fun insert(
        tenant: TenantId,
        row: DueDateExtension,
    ): Boolean =
        runner.inTransaction {
            dsl
                .insertInto(t)
                .set(t.TENANT_ID, tenant.value)
                .set(t.ENTITY_REF, row.entityRef)
                .set(t.PERIOD_REF, row.periodRef)
                .set(t.TARGET, row.target.name)
                .set(t.SCOPE_KIND, row.scopeKind.name)
                .set(t.SCOPE_REF, row.scopeRef)
                .set(t.ORIGINAL_DUE_DATE, row.originalDueDate)
                .set(t.EXTENDED_DUE_DATE, row.extendedDueDate)
                .set(t.EXTENDED_FILING_DUE_DATE, row.extendedFilingDueDate)
                .set(t.IRAS_REFERENCE, row.irasReference)
                .set(t.SOURCE_EVENT_ID, row.sourceEventId)
                .onConflict(t.TENANT_ID, t.SOURCE_EVENT_ID)
                .doNothing()
                .execute() == 1
        }

    override fun forPeriod(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
    ): List<DueDateExtension> =
        runner.inTransaction {
            dsl
                .selectFrom(t)
                .where(t.TENANT_ID.eq(tenant.value))
                .and(t.ENTITY_REF.eq(entityRef))
                .and(t.PERIOD_REF.eq(periodRef))
                .orderBy(t.RECORDED_AT)
                .fetch()
                .map {
                    DueDateExtension(
                        entityRef = it.entityRef!!,
                        periodRef = it.periodRef!!,
                        target = ExtensionTarget.valueOf(it.target!!),
                        scopeKind = ExtensionScopeKind.valueOf(it.scopeKind!!),
                        scopeRef = it.scopeRef,
                        originalDueDate = it.originalDueDate,
                        extendedDueDate = it.extendedDueDate!!,
                        extendedFilingDueDate = it.extendedFilingDueDate,
                        irasReference = it.irasReference!!,
                        sourceEventId = it.sourceEventId!!,
                    )
                }
        }
}
