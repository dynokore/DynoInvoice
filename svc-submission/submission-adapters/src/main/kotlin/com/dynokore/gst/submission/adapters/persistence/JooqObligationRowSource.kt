package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.compliance.DocumentCoverage
import com.dynokore.gst.submission.application.compliance.ObligationRowSource
import com.dynokore.gst.submission.domain.metrics.ObligationRow
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * [ObligationRowSource] over the register entries joined to their version rows (INV-REQ-1069, INV-Q-12800 A): one
 * read transaction, tenant in the WHERE clause as well as the row level security (CS-19), no write, no cache.
 */
class JooqObligationRowSource(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : ObligationRowSource {
    override fun rows(
        tenant: TenantId,
        entityRef: String?,
        periodRef: String?,
    ): List<ObligationRow> {
        val entry = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
        val version = Submission.SUBMISSION_
        val records =
            runner.inTransaction {
                dsl
                    .select(
                        entry.DOC_UUID,
                        entry.ENTITY_REF,
                        entry.PERIOD_REF,
                        entry.DUE_DATE,
                        entry.IN_SCOPE,
                        entry.KIND,
                        version.STATE,
                    ).from(entry)
                    .leftJoin(version)
                    .on(version.DOC_UUID.eq(entry.DOC_UUID))
                    .and(version.TENANT_ID.eq(entry.TENANT_ID))
                    .where(entry.TENANT_ID.eq(tenant.value))
                    .and(entityRef?.let { entry.ENTITY_REF.eq(it) } ?: DSL.noCondition())
                    .and(periodRef?.let { entry.PERIOD_REF.eq(it) } ?: DSL.noCondition())
                    .orderBy(entry.DOC_UUID.asc(), version.VERSION.asc())
                    .fetch()
            }
        return records
            .groupBy { checkNotNull(it.get(entry.DOC_UUID)) }
            .map { (docUuid, group) ->
                val first = group.first()
                val states = group.mapNotNull { it.get(version.STATE) }.map(LifecycleState::valueOf)
                ObligationRow(
                    docUuid = docUuid,
                    entityRef = checkNotNull(first.get(entry.ENTITY_REF)),
                    periodRef = checkNotNull(first.get(entry.PERIOD_REF)),
                    dueDate = checkNotNull(first.get(entry.DUE_DATE)),
                    inScope = checkNotNull(first.get(entry.IN_SCOPE)),
                    hasSubmission = DocumentCoverage.hasSubmission(states),
                    kind = RowKind.valueOf(checkNotNull(first.get(entry.KIND))),
                    versionCount = maxOf(states.size, 1),
                )
            }
    }
}
