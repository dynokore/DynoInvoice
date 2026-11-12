package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.queue.InFlightBreach
import com.dynokore.gst.submission.application.queue.InFlightStore
import com.dynokore.gst.submission.application.replay.ReplayRequeue
import com.dynokore.gst.submission.application.replay.RequeueOutcome
import com.dynokore.gst.submission.domain.ordering.InFlight
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.retry.ReplayResult
import com.dynokore.gst.submission.domain.retry.RetryPlanner
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.QueueEntry
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.postgresql.util.PSQLException

/**
 * [ReplayRequeue] (INV-REQ-575 behaviours 2 and 3, delegated ruling of owner 2026-10-14): in one tenant transaction
 * the rows of the Doc UUID are locked `FOR UPDATE` in version order, [RetryPlanner.onReplay] decides, and a
 * `DEAD_LETTERED` version returns to `QUEUED` with its retry series cleared and one item on the replay lane. A
 * version already `QUEUED` answers ALREADY_QUEUED; another in-flight version of the Doc UUID, or a refusal by the
 * in-flight index (the transaction rolls back), answers IN_FLIGHT_CONFLICT with nothing written. A version in any
 * other state, or with no row, throws (fail closed): the port has no outcome for it.
 */
class JooqReplayRequeue(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
    private val versions: InFlightStore,
    private val queue: WorkQueue,
) : ReplayRequeue {
    override fun requeue(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): RequeueOutcome =
        try {
            replayInTransaction(tenant, docUuid, version)
        } catch (e: InFlightBreach) {
            // the transaction rolled back with the refused write: nothing was written (SUB-008)
            LOG.log(System.Logger.Level.INFO, "${e.code}: replay of $docUuid version $version skipped")
            RequeueOutcome.IN_FLIGHT_CONFLICT
        }

    private fun replayInTransaction(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): RequeueOutcome =
        runner.inTransaction {
            val rows = versions.lockVersions(tenant, docUuid)
            val state =
                checkNotNull(rows.firstOrNull { it.version == version }?.state) {
                    "submission $docUuid version $version does not exist"
                }
            when (val planned = RetryPlanner.onReplay(docUuid, version, state)) {
                is ReplayResult.Unchanged -> {
                    RequeueOutcome.ALREADY_QUEUED
                }

                is ReplayResult.Replayed -> {
                    if (rows.any { it.version != version && InFlight.isInFlight(it.state) }) {
                        RequeueOutcome.IN_FLIGHT_CONFLICT
                    } else {
                        requeueLocked(tenant, docUuid, version)
                    }
                }

                else -> {
                    error("replay of $docUuid version $version: $planned")
                }
            }
        }

    private fun requeueLocked(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): RequeueOutcome {
        val s = Submission.SUBMISSION_
        val updated =
            try {
                dsl
                    .update(s)
                    .set(s.STATE, LifecycleState.QUEUED.name)
                    .set(s.RETRY_FAILURES, 0)
                    .setNull(s.RETRY_FIRST_FAILURE_AT)
                    .setNull(s.NEXT_ATTEMPT_AT)
                    .where(s.TENANT_ID.eq(tenant.value))
                    .and(s.DOC_UUID.eq(docUuid))
                    .and(s.VERSION.eq(version))
                    .and(s.STATE.eq(LifecycleState.DEAD_LETTERED.name))
                    .execute()
            } catch (e: DataAccessException) {
                if (violatedConstraint(e) == InFlight.INDEX_NAME) throw InFlightBreach(docUuid, version, e)
                throw e
            }
        check(updated == 1) { "the locked submission row $docUuid version $version was not updated" }
        queue.enqueue(
            QueueEntry(
                partitionKey = docUuid,
                lane = Lane.REPLAY,
                payloadJson = """{"docUuid":"$docUuid","version":$version}""",
            ),
        )
        return RequeueOutcome.REQUEUED
    }

    private companion object {
        private val LOG = System.getLogger(JooqReplayRequeue::class.java.name)
        private const val UNIQUE_VIOLATION = "23505"

        fun violatedConstraint(e: Throwable): String? =
            generateSequence(e) { it.cause }
                .filterIsInstance<PSQLException>()
                .firstOrNull { it.sqlState == UNIQUE_VIOLATION }
                ?.serverErrorMessage
                ?.constraint
    }
}
