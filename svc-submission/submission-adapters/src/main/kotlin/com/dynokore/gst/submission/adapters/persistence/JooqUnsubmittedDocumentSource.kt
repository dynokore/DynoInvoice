package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.compliance.DocumentCoverage
import com.dynokore.gst.submission.application.offboarding.UnsubmittedDocumentSource
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * [UnsubmittedDocumentSource] over the in-scope register entries joined to their version rows (INV-REQ-956): one
 * read transaction, tenant in the WHERE clause as well as the row level security (CS-19), folded per document with
 * [DocumentCoverage.hasSubmission], no extension rows, no write. A failed read throws.
 */
class JooqUnsubmittedDocumentSource(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : UnsubmittedDocumentSource {
    override fun count(
        tenant: TenantId,
        entityRef: String?,
    ): Int {
        val entry = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
        val version = Submission.SUBMISSION_
        val records =
            runner.inTransaction {
                dsl
                    .select(entry.DOC_UUID, version.STATE)
                    .from(entry)
                    .leftJoin(version)
                    .on(version.DOC_UUID.eq(entry.DOC_UUID))
                    .and(version.TENANT_ID.eq(entry.TENANT_ID))
                    .where(entry.TENANT_ID.eq(tenant.value))
                    .and(entry.IN_SCOPE.isTrue)
                    .and(entityRef?.let { entry.ENTITY_REF.eq(it) } ?: DSL.noCondition())
                    .orderBy(entry.DOC_UUID.asc(), version.VERSION.asc())
                    .fetch()
            }
        return records
            .groupBy { checkNotNull(it.get(entry.DOC_UUID)) }
            .values
            .count { group ->
                val states = group.mapNotNull { it.get(version.STATE) }.map(LifecycleState::valueOf)
                !DocumentCoverage.hasSubmission(states)
            }
    }
}
