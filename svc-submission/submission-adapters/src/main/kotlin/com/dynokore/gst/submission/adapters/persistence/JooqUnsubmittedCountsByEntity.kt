package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.compliance.DocumentCoverage
import com.dynokore.gst.submission.application.offboarding.UnsubmittedCountsByEntity
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext

/**
 * [UnsubmittedCountsByEntity] over the in-scope register entries joined to their version rows (INV-REQ-956), the same
 * set as [JooqUnsubmittedDocumentSource]: one read transaction, tenant in the WHERE clause as well as the row level
 * security (CS-19), folded per document with [DocumentCoverage.hasSubmission], grouped by entity. Entities with 0 are
 * omitted. A failed read throws.
 */
class JooqUnsubmittedCountsByEntity(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : UnsubmittedCountsByEntity {
    override fun counts(tenant: TenantId): Map<String, Int> {
        val entry = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
        val version = Submission.SUBMISSION_
        val records =
            runner.inTransaction {
                dsl
                    .select(entry.DOC_UUID, entry.ENTITY_REF, version.STATE)
                    .from(entry)
                    .leftJoin(version)
                    .on(version.DOC_UUID.eq(entry.DOC_UUID))
                    .and(version.TENANT_ID.eq(entry.TENANT_ID))
                    .where(entry.TENANT_ID.eq(tenant.value))
                    .and(entry.IN_SCOPE.isTrue)
                    .orderBy(entry.DOC_UUID.asc(), version.VERSION.asc())
                    .fetch()
            }
        return records
            .groupBy { checkNotNull(it.get(entry.DOC_UUID)) }
            .values
            .filter { group ->
                val states = group.mapNotNull { it.get(version.STATE) }.map(LifecycleState::valueOf)
                !DocumentCoverage.hasSubmission(states)
            }.groupingBy { checkNotNull(it.first().get(entry.ENTITY_REF)) }
            .eachCount()
    }
}
