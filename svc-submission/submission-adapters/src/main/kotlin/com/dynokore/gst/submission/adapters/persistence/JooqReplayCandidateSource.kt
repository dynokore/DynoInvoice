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
import org.jooq.Field
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL
import java.time.OffsetDateTime

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
        // First and last attempt of a version are derived tables (rank 1 by ascending or descending attempt number),
        // never generated-table aliases: the tenancy verifier knows only the declared tables.
        val lastRank = rankOf("last_rank", descending = true)
        val firstRank = rankOf("first_rank", descending = false)
        val last = attemptRanked(lastRank, "last_attempt")
        val first = attemptRanked(firstRank, "first_attempt")
        val failureClass = checkNotNull(last.field(attempt.FAILURE_CLASS))
        val requestedAt = checkNotNull(first.field(attempt.REQUESTED_AT))
        val records =
            runner.inTransaction {
                dsl
                    .select(
                        version.DOC_UUID,
                        version.VERSION,
                        version.ENTITY_REF,
                        entry.PERIOD_REF,
                        entry.DUE_DATE,
                        failureClass,
                        requestedAt,
                    ).from(version)
                    .leftJoin(entry)
                    .on(entry.DOC_UUID.eq(version.DOC_UUID))
                    .and(entry.TENANT_ID.eq(version.TENANT_ID))
                    .leftJoin(last)
                    .on(checkNotNull(last.field(attempt.TENANT_ID)).eq(version.TENANT_ID))
                    .and(checkNotNull(last.field(attempt.DOC_UUID)).eq(version.DOC_UUID))
                    .and(checkNotNull(last.field(attempt.VERSION)).eq(version.VERSION))
                    .and(checkNotNull(last.field(lastRank)).eq(1))
                    .leftJoin(first)
                    .on(checkNotNull(first.field(attempt.TENANT_ID)).eq(version.TENANT_ID))
                    .and(checkNotNull(first.field(attempt.DOC_UUID)).eq(version.DOC_UUID))
                    .and(checkNotNull(first.field(attempt.VERSION)).eq(version.VERSION))
                    .and(checkNotNull(first.field(firstRank)).eq(1))
                    .where(version.TENANT_ID.eq(tenant.value))
                    .and(version.ENTITY_REF.eq(entityRef))
                    .and(version.STATE.eq(LifecycleState.DEAD_LETTERED.name))
                    .fetch()
            }
        return records.mapNotNull { toCandidate(it, entityRef, failureClass, requestedAt) }
    }

    private fun rankOf(
        name: String,
        descending: Boolean,
    ): Field<Int> =
        DSL
            .rowNumber()
            .over(
                DSL
                    .partitionBy(attempt.TENANT_ID, attempt.DOC_UUID, attempt.VERSION)
                    .orderBy(if (descending) attempt.ATTEMPT_NUMBER.desc() else attempt.ATTEMPT_NUMBER.asc()),
            ).`as`(name)

    private fun attemptRanked(
        rank: Field<Int>,
        name: String,
    ): Table<*> =
        DSL
            .select(
                attempt.TENANT_ID,
                attempt.DOC_UUID,
                attempt.VERSION,
                attempt.FAILURE_CLASS,
                attempt.REQUESTED_AT,
                rank,
            ).from(attempt)
            .asTable(DSL.name(name))

    private fun toCandidate(
        r: Record,
        entityRef: String,
        failureClass: Field<String?>,
        requestedAt: Field<OffsetDateTime?>,
    ): ReplayCandidate? {
        val doc = checkNotNull(r.get(version.DOC_UUID))
        val number = checkNotNull(r.get(version.VERSION))
        val period = r.get(entry.PERIOD_REF)
        val due = r.get(entry.DUE_DATE)
        val queuedAt = r.get(requestedAt)
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
            failureClass = r.get(failureClass),
            dueDate = due,
            queuedAt = queuedAt.toInstant(),
        )
    }

    private companion object {
        private val LOG = System.getLogger(JooqReplayCandidateSource::class.java.name)
    }
}
