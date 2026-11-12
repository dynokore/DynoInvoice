package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.SubmissionAttempt
import com.dynokore.gst.submission.application.backlog.BacklogRead
import com.dynokore.gst.submission.application.backlog.BacklogVersionSource
import com.dynokore.gst.submission.domain.metrics.BacklogVersion
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.OffsetDateTime

/**
 * [BacklogVersionSource] over the version rows of one tenant joined to the register entry of the same document
 * (INV-REQ-1370, INV-AC-20581): one select in one read transaction, tenant in the WHERE clause as well as the row level
 * security. A row without a register entry or without a due date is skipped and counted. A pending version (QUEUED,
 * RETRY, DEAD_LETTERED) with a NULL `queued_at` takes the `requested_at` of its earliest attempt row; with no attempt
 * row it is skipped and counted, never mapped with an invented instant. Values are never logged.
 */
class JooqBacklogVersionSource(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : BacklogVersionSource {
    private val entry = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
    private val version = Submission.SUBMISSION_
    private val attempt = SubmissionAttempt.SUBMISSION_ATTEMPT

    override fun versions(tenant: TenantId): BacklogRead {
        val (records, firstAttempts) =
            runner.inTransaction {
                val rows =
                    dsl
                        .select(
                            version.DOC_UUID,
                            version.VERSION,
                            version.STATE,
                            version.QUEUED_AT,
                            entry.ENTITY_REF,
                            entry.PERIOD_REF,
                            entry.DUE_DATE,
                        ).from(version)
                        .leftJoin(entry)
                        .on(entry.DOC_UUID.eq(version.DOC_UUID))
                        .and(entry.TENANT_ID.eq(version.TENANT_ID))
                        .where(version.TENANT_ID.eq(tenant.value))
                        .fetch()
                rows to firstAttempts(tenant)
            }
        val mapped =
            records.mapNotNull {
                toVersion(
                    it,
                    firstAttempts[
                        it.get(version.DOC_UUID) to
                            it.get(version.VERSION),
                    ],
                )
            }
        return BacklogRead(mapped, records.size - mapped.size)
    }

    /** The earliest `requested_at` per (doc, version) of the tenant. */
    private fun firstAttempts(tenant: TenantId): Map<Pair<String?, Int?>, OffsetDateTime?> {
        val first = DSL.min(attempt.REQUESTED_AT)
        return dsl
            .select(attempt.DOC_UUID, attempt.VERSION, first)
            .from(attempt)
            .where(attempt.TENANT_ID.eq(tenant.value))
            .groupBy(attempt.DOC_UUID, attempt.VERSION)
            .fetch()
            .associate { (it.get(attempt.DOC_UUID) to it.get(attempt.VERSION)) to it.get(first) }
    }

    private fun toVersion(
        r: Record,
        earliest: OffsetDateTime?,
    ): BacklogVersion? {
        val entity = r.get(entry.ENTITY_REF)
        val period = r.get(entry.PERIOD_REF)
        val due = r.get(entry.DUE_DATE)
        val state = LifecycleState.valueOf(checkNotNull(r.get(version.STATE)))
        val queuedAt = r.get(version.QUEUED_AT) ?: earliest
        // A non-pending version's queued instant enters no value (BacklogMetrics reads it for pending only); the same
        // earliest-attempt rule gives it one, and a row with neither is skipped, never given a made-up instant.
        val register = if (entity == null || period == null) null else entity to period
        return if (register == null || due == null || queuedAt == null) {
            null
        } else {
            BacklogVersion(
                docUuid = checkNotNull(r.get(version.DOC_UUID)),
                version = checkNotNull(r.get(version.VERSION)),
                entityRef = register.first,
                periodRef = register.second,
                state = state,
                queuedAt = queuedAt.toInstant(),
                dueDate = due,
            )
        }
    }
}
