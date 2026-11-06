package com.dynokore.gst.controlPlane.adapters.persistence

import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.ACCREDITATION_STEP_PENDING
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationStepQueue
import com.dynokore.gst.controlPlane.application.accreditation.PendingStep
import com.dynokore.gst.controlPlane.application.accreditation.ReleaseTarget
import com.dynokore.gst.controlPlane.application.accreditation.StepRetryPolicy
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import org.jooq.Condition
import org.jooq.DSLContext
import java.time.Instant
import java.time.ZoneOffset

private const val PENDING = "PENDING"
private const val EXHAUSTED = "EXHAUSTED"
private const val MAX_ERROR = 500

private fun targetIs(target: ReleaseTarget): Condition =
    ACCREDITATION_STEP_PENDING.RELEASE_KIND
        .eq(target.kind.name)
        .and(ACCREDITATION_STEP_PENDING.RELEASE_REF.eq(target.ref))

/**
 * Writes the pending row of a release. Called by the repository save that stores the release record, so the row is
 * part of the release transaction (the dual write of the transactional outbox pattern). `next_attempt_at` is the
 * creation time plus [firstDelay], so a process that dies after the commit is still picked up by the relay.
 */
fun insertPendingStep(
    dsl: DSLContext,
    step: PendingStep,
    firstDelay: java.time.Duration,
) {
    dsl
        .insertInto(ACCREDITATION_STEP_PENDING)
        .set(ACCREDITATION_STEP_PENDING.RELEASE_KIND, step.target.kind.name)
        .set(ACCREDITATION_STEP_PENDING.RELEASE_REF, step.target.ref)
        .set(ACCREDITATION_STEP_PENDING.ASSESSMENT_REQUESTED, step.assessmentRequested)
        .set(ACCREDITATION_STEP_PENDING.CORRELATION_ID, step.request.correlationId)
        .set(ACCREDITATION_STEP_PENDING.CAUSATION_ID, step.request.causationId)
        .set(ACCREDITATION_STEP_PENDING.STATUS, PENDING)
        .set(ACCREDITATION_STEP_PENDING.ATTEMPTS, 0)
        .set(ACCREDITATION_STEP_PENDING.NEXT_ATTEMPT_AT, step.createdAt.plus(firstDelay).atOffset(ZoneOffset.UTC))
        .set(ACCREDITATION_STEP_PENDING.CREATED_AT, step.createdAt.atOffset(ZoneOffset.UTC))
        .onConflict(ACCREDITATION_STEP_PENDING.RELEASE_KIND, ACCREDITATION_STEP_PENDING.RELEASE_REF)
        .doNothing()
        .execute()
}

/** The table `accreditation_step_pending` through jOOQ (TPL-11): the relay reads, claims and updates; the step deletes. */
class AccreditationStepQueueJooq(
    private val dsl: DSLContext,
    private val policy: StepRetryPolicy,
) : AccreditationStepQueue {
    override fun due(
        now: Instant,
        limit: Int,
    ): List<ReleaseTarget> =
        dsl
            .select(ACCREDITATION_STEP_PENDING.RELEASE_KIND, ACCREDITATION_STEP_PENDING.RELEASE_REF)
            .from(ACCREDITATION_STEP_PENDING)
            .where(ACCREDITATION_STEP_PENDING.STATUS.eq(PENDING))
            .and(ACCREDITATION_STEP_PENDING.NEXT_ATTEMPT_AT.le(now.atOffset(ZoneOffset.UTC)))
            .orderBy(
                ACCREDITATION_STEP_PENDING.CREATED_AT.asc(),
                ACCREDITATION_STEP_PENDING.RELEASE_KIND.asc(),
                ACCREDITATION_STEP_PENDING.RELEASE_REF.asc(),
            ).limit(limit)
            .fetch()
            .map {
                ReleaseTarget(
                    ReleaseTarget.Kind.valueOf(checkNotNull(it[ACCREDITATION_STEP_PENDING.RELEASE_KIND])),
                    checkNotNull(it[ACCREDITATION_STEP_PENDING.RELEASE_REF]),
                )
            }

    override fun claim(
        target: ReleaseTarget,
        now: Instant,
    ): PendingStep? =
        dsl
            .selectFrom(ACCREDITATION_STEP_PENDING)
            .where(targetIs(target))
            .and(ACCREDITATION_STEP_PENDING.STATUS.eq(PENDING))
            .and(ACCREDITATION_STEP_PENDING.NEXT_ATTEMPT_AT.le(now.atOffset(ZoneOffset.UTC)))
            .forUpdate()
            .skipLocked()
            .fetchOne()
            ?.let {
                PendingStep(
                    target,
                    checkNotNull(it.assessmentRequested),
                    RequestContext(checkNotNull(it.correlationId), checkNotNull(it.causationId)),
                    checkNotNull(it.createdAt).toInstant(),
                )
            }

    override fun delete(target: ReleaseTarget) {
        dsl.deleteFrom(ACCREDITATION_STEP_PENDING).where(targetIs(target)).execute()
    }

    override fun recordFailure(
        target: ReleaseTarget,
        error: String,
        now: Instant,
    ): Boolean {
        val row =
            dsl
                .selectFrom(ACCREDITATION_STEP_PENDING)
                .where(targetIs(target))
                .and(ACCREDITATION_STEP_PENDING.STATUS.eq(PENDING))
                .forUpdate()
                .fetchOne() ?: return false
        val attempts = checkNotNull(row.attempts) + 1
        val exhausted = attempts >= policy.maxAttempts
        dsl
            .update(ACCREDITATION_STEP_PENDING)
            .set(ACCREDITATION_STEP_PENDING.ATTEMPTS, attempts)
            .set(ACCREDITATION_STEP_PENDING.STATUS, if (exhausted) EXHAUSTED else PENDING)
            .set(
                ACCREDITATION_STEP_PENDING.NEXT_ATTEMPT_AT,
                now.plus(policy.backoff(attempts)).atOffset(ZoneOffset.UTC),
            ).set(ACCREDITATION_STEP_PENDING.LAST_ERROR, error.take(MAX_ERROR))
            .where(targetIs(target))
            .execute()
        return exhausted
    }
}
