package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.SubmissionAttempt
import com.dynokore.gst.submission.application.replay.ReplayCandidateSource
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.replay.ReplayCandidate
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL

/**
 * [ReplayCandidateSource] (INV-REQ-575, delegated ruling of owner 2026-10-14): the `DEAD_LETTERED` versions of one
 * entity in one select in one read transaction of the bound tenant, tenant in the WHERE clause as well as the row
 * level security (CS-19). The register entry joined on (tenant, Doc UUID) gives the period and the due date; the
 * class is the `failure_class` of the attempt with the highest attempt number (null when none is stored). `queuedAt`
 * is the `requested_at` of the first attempt. A version without a register entry or without a due date, or without
 * an attempt, cannot form a candidate: it is counted and logged by identifiers only, never mapped with an invented
 * value.
 */
class JooqReplayCandidateSource(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : ReplayCandidateSource {
    private val entry = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
    private val version = Submission.SUBMISSION_
    private val attempt = SubmissionAttempt.SUBMISSION_ATTEMPT

    override fun candidates(
        tenant: TenantId,
        entityRef: String,
    ): List<ReplayCandidate> {
        val last = attempt.`as`("last_attempt")
        val first = attempt.`as`("first_attempt")
        val records =
            runner.inTransaction {
                dsl
                    .select(
                        version.DOC_UUID,
                        version.VERSION,
                        version.ENTITY_REF,
                        entry.PERIOD_REF,
                        entry.DUE_DATE,
                        last.FAILURE_CLASS,
                        first.REQUESTED_AT,
                    ).from(version)
                    .leftJoin(entry)
                    .on(entry.DOC_UUID.eq(version.DOC_UUID))
                    .and(entry.TENANT_ID.eq(version.TENANT_ID))
                    .leftJoin(last)
                    .on(last.TENANT_ID.eq(version.TENANT_ID))
                    .and(last.DOC_UUID.eq(version.DOC_UUID))
                    .and(last.VERSION.eq(version.VERSION))
                    .and(last.ATTEMPT_NUMBER.eq(maxAttempt(version, attempt, true)))
                    .leftJoin(first)
                    .on(first.TENANT_ID.eq(version.TENANT_ID))
                    .and(first.DOC_UUID.eq(version.DOC_UUID))
                    .and(first.VERSION.eq(version.VERSION))
                    .and(first.ATTEMPT_NUMBER.eq(maxAttempt(version, attempt, false)))
                    .where(version.TENANT_ID.eq(tenant.value))
                    .and(version.ENTITY_REF.eq(entityRef))
                    .and(version.STATE.eq(LifecycleState.DEAD_LETTERED.name))
                    .fetch()
            }
        return records.mapNotNull { toCandidate(it, entityRef, last, first) }
    }

    private fun maxAttempt(
        v: Submission,
        a: SubmissionAttempt,
        highest: Boolean,
    ) = dsl
        .select(if (highest) DSL.max(a.ATTEMPT_NUMBER) else DSL.min(a.ATTEMPT_NUMBER))
        .from(a)
        .where(a.TENANT_ID.eq(v.TENANT_ID))
        .and(a.DOC_UUID.eq(v.DOC_UUID))
        .and(a.VERSION.eq(v.VERSION))
        .asField<Int>()

    private fun toCandidate(
        r: Record,
        entityRef: String,
        last: SubmissionAttempt,
        first: SubmissionAttempt,
    ): ReplayCandidate? {
        val doc = checkNotNull(r.get(version.DOC_UUID))
        val number = checkNotNull(r.get(version.VERSION))
        val period = r.get(entry.PERIOD_REF)
        val due = r.get(entry.DUE_DATE)
        val queuedAt = r.get(first.REQUESTED_AT)
        if (period == null || due == null || queuedAt == null) {
            LOG.log(
                System.Logger.Level.WARNING,
                "dead-lettered $doc version $number is not a replay candidate: no register entry, due date or attempt",
            )
            return null
        }
        return ReplayCandidate(
            docUuid = doc,
            version = number,
            entityRef = entityRef,
            periodRef = period,
            state = LifecycleState.DEAD_LETTERED,
            failureClass = r.get(last.FAILURE_CLASS),
            dueDate = due,
            queuedAt = queuedAt.toInstant(),
        )
    }

    private companion object {
        private val LOG = System.getLogger(JooqReplayCandidateSource::class.java.name)
    }
}
