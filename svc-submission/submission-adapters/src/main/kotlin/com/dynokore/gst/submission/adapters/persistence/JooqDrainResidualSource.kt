package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ProvisionalRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.SubmissionAttempt
import com.dynokore.gst.submission.application.drain.ResidualCandidate
import com.dynokore.gst.submission.application.drain.ResidualKind
import com.dynokore.gst.submission.application.drain.ResidualSource
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Table
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL

/**
 * [ResidualSource] (INV-REQ-554, INV-AC-20847): for a tenant, entity and period, every provisional register entry
 * (kind PROVISIONAL, version 1, its stored `reason_code`) and every DEAD_LETTERED version (kind DEAD_LETTERED, the
 * stored `failure_class` of the attempt with the highest attempt number, which is the catalogue failure code of
 * INV-T-20651). No other state is read. The period of a version is that of its register entry. The recorded
 * instant of a provisional entry is `held_at`, that of a dead letter is `requested_at` of its last attempt. A null
 * code is returned as null, never invented. The tenant is in every predicate as well as the row level security; a
 * read error returns null (fail closed). Values are never logged.
 */
class JooqDrainResidualSource(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : ResidualSource {
    private val provisional = ProvisionalRegisterEntry.PROVISIONAL_REGISTER_ENTRY
    private val entry = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
    private val version = Submission.SUBMISSION_
    private val attempt = SubmissionAttempt.SUBMISSION_ATTEMPT

    override fun candidates(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
    ): List<ResidualCandidate>? =
        try {
            runner.inTransaction {
                provisionals(tenant, entityRef, periodRef) + deadLetters(tenant, entityRef, periodRef)
            }
        } catch (ignored: DataAccessException) {
            null
        } catch (ignored: IllegalStateException) {
            null
        }

    private fun provisionals(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
    ): List<ResidualCandidate> =
        dsl
            .select(provisional.DOC_UUID, provisional.REASON_CODE, provisional.HELD_AT)
            .from(provisional)
            .where(provisional.TENANT_ID.eq(tenant.value))
            .and(provisional.ENTITY_REF.eq(entityRef))
            .and(provisional.PERIOD_REF.eq(periodRef))
            .orderBy(provisional.HELD_AT, provisional.DOC_UUID)
            .fetch()
            .map {
                ResidualCandidate(
                    docUuid = checkNotNull(it.get(provisional.DOC_UUID)),
                    version = FIRST_VERSION,
                    kind = ResidualKind.PROVISIONAL,
                    reasonCode = it.get(provisional.REASON_CODE),
                    recordedAt = checkNotNull(it.get(provisional.HELD_AT)).toInstant(),
                )
            }

    private fun deadLetters(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
    ): List<ResidualCandidate> {
        val rank = lastRank()
        val last = lastAttempts(rank)
        val failureClass = checkNotNull(last.field(attempt.FAILURE_CLASS))
        val requestedAt = checkNotNull(last.field(attempt.REQUESTED_AT))
        return dsl
            .select(version.DOC_UUID, version.VERSION, failureClass, requestedAt)
            .from(version)
            .join(entry)
            .on(entry.DOC_UUID.eq(version.DOC_UUID))
            .and(entry.TENANT_ID.eq(version.TENANT_ID))
            .leftJoin(last)
            .on(checkNotNull(last.field(attempt.TENANT_ID)).eq(version.TENANT_ID))
            .and(checkNotNull(last.field(attempt.DOC_UUID)).eq(version.DOC_UUID))
            .and(checkNotNull(last.field(attempt.VERSION)).eq(version.VERSION))
            .and(checkNotNull(last.field(rank)).eq(1))
            .where(version.TENANT_ID.eq(tenant.value))
            .and(version.STATE.eq(LifecycleState.DEAD_LETTERED.name))
            .and(version.ENTITY_REF.eq(entityRef))
            .and(entry.PERIOD_REF.eq(periodRef))
            .orderBy(version.DOC_UUID, version.VERSION)
            .fetch()
            .map {
                ResidualCandidate(
                    docUuid = checkNotNull(it.get(version.DOC_UUID)),
                    version = checkNotNull(it.get(version.VERSION)),
                    kind = ResidualKind.DEAD_LETTERED,
                    reasonCode = it.get(failureClass),
                    recordedAt = checkNotNull(it.get(requestedAt)) { "no attempt instant" }.toInstant(),
                )
            }
    }

    // The last attempt of each version is a derived table (rank 1 by descending attempt number), never a
    // generated-table alias: the tenancy verifier knows only the declared tables and rewrites inside the derived one.
    private fun lastRank(): Field<Int> =
        DSL
            .rowNumber()
            .over(
                DSL
                    .partitionBy(attempt.TENANT_ID, attempt.DOC_UUID, attempt.VERSION)
                    .orderBy(attempt.ATTEMPT_NUMBER.desc()),
            ).`as`("attempt_rank")

    private fun lastAttempts(rank: Field<Int>): Table<*> =
        DSL
            .select(
                attempt.TENANT_ID,
                attempt.DOC_UUID,
                attempt.VERSION,
                attempt.FAILURE_CLASS,
                attempt.REQUESTED_AT,
                rank,
            ).from(attempt)
            .asTable(DSL.name("last_attempt"))

    private companion object {
        const val FIRST_VERSION = 1
    }
}
