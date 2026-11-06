package com.dynokore.gst.controlPlane.adapters.persistence

import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.records.AccreditationTaskRecord
import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.ACCREDITATION_TASK
import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.PACK_RELEASE_RECORD
import com.dynokore.gst.controlPlane.adapters.persistence.jooq.tables.references.RELEASE_TRAIN
import com.dynokore.gst.controlPlane.application.accreditation.AccreditationTaskRepository
import com.dynokore.gst.controlPlane.application.accreditation.ReleaseAccreditationLinks
import com.dynokore.gst.controlPlane.application.accreditation.ReleaseTarget
import com.dynokore.gst.controlPlane.application.accreditation.TaskCursorKey
import com.dynokore.gst.controlPlane.domain.accreditation.AccreditationTask
import com.dynokore.gst.controlPlane.domain.accreditation.TaskReason
import com.dynokore.gst.controlPlane.domain.accreditation.TaskStatus
import com.dynokore.gst.controlPlane.domain.release.AccreditationCheck
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant
import java.time.ZoneOffset

/** Accreditation tasks in the `controlplane` schema through jOOQ (TPL-11): insert, read and conclude only. */
class AccreditationTaskRepositoryJooq(
    private val dsl: DSLContext,
) : AccreditationTaskRepository {
    override fun find(taskRef: String): AccreditationTask? =
        dsl
            .selectFrom(ACCREDITATION_TASK)
            .where(ACCREDITATION_TASK.TASK_REF.eq(taskRef))
            .fetchOne()
            ?.let(::taskOf)

    override fun findFor(
        releaseRef: String,
        reason: TaskReason,
    ): AccreditationTask? =
        dsl
            .selectFrom(ACCREDITATION_TASK)
            .where(ACCREDITATION_TASK.RELEASE_REF.eq(releaseRef))
            .and(ACCREDITATION_TASK.REASON.eq(reason.name))
            .fetchOne()
            ?.let(::taskOf)

    override fun insert(task: AccreditationTask): Boolean =
        dsl
            .insertInto(ACCREDITATION_TASK)
            .set(ACCREDITATION_TASK.TASK_REF, task.taskRef)
            .set(ACCREDITATION_TASK.STATUS, task.status.name)
            .set(ACCREDITATION_TASK.REASON, task.reason.name)
            .set(ACCREDITATION_TASK.OPENED_AT, task.openedAt.atOffset(ZoneOffset.UTC))
            .set(ACCREDITATION_TASK.ALERT_REF, task.alertRef)
            .set(ACCREDITATION_TASK.RELEASE_REF, task.releaseRef)
            .onConflict(ACCREDITATION_TASK.RELEASE_REF, ACCREDITATION_TASK.REASON)
            .doNothing()
            .execute() == 1

    override fun page(
        status: TaskStatus?,
        after: TaskCursorKey?,
        limit: Int,
    ): List<AccreditationTask> {
        var where: Condition = DSL.trueCondition()
        if (status != null) where = where.and(ACCREDITATION_TASK.STATUS.eq(status.name))
        if (after != null) {
            val at = after.openedAt.atOffset(ZoneOffset.UTC)
            where =
                where.and(
                    ACCREDITATION_TASK.OPENED_AT
                        .lt(at)
                        .or(ACCREDITATION_TASK.OPENED_AT.eq(at).and(ACCREDITATION_TASK.TASK_REF.gt(after.taskRef))),
                )
        }
        return dsl
            .selectFrom(ACCREDITATION_TASK)
            .where(where)
            .orderBy(ACCREDITATION_TASK.OPENED_AT.desc(), ACCREDITATION_TASK.TASK_REF.asc())
            .limit(limit)
            .fetch()
            .map(::taskOf)
    }

    override fun conclude(
        taskRef: String,
        at: Instant,
        conclusion: String,
    ): AccreditationTask? {
        dsl
            .update(ACCREDITATION_TASK)
            .set(ACCREDITATION_TASK.STATUS, TaskStatus.CONCLUDED.name)
            .set(ACCREDITATION_TASK.CONCLUDED_AT, at.atOffset(ZoneOffset.UTC))
            .set(ACCREDITATION_TASK.CONCLUSION, conclusion)
            .where(ACCREDITATION_TASK.TASK_REF.eq(taskRef))
            .and(ACCREDITATION_TASK.STATUS.eq(TaskStatus.OPEN.name))
            .execute()
        return find(taskRef)
    }

    private fun taskOf(row: AccreditationTaskRecord) =
        AccreditationTask(
            checkNotNull(row.taskRef),
            TaskStatus.valueOf(checkNotNull(row.status)),
            TaskReason.valueOf(checkNotNull(row.reason)),
            checkNotNull(row.openedAt).toInstant(),
            checkNotNull(row.alertRef),
            row.releaseRef,
            row.concludedAt?.toInstant(),
            row.conclusion,
        )
}

/** The accreditation check held by a train row or a pack version's release record. */
class ReleaseAccreditationLinksJooq(
    private val dsl: DSLContext,
) : ReleaseAccreditationLinks {
    override fun current(target: ReleaseTarget): AccreditationCheck? =
        when (target.kind) {
            ReleaseTarget.Kind.TRAIN -> {
                dsl
                    .selectFrom(RELEASE_TRAIN)
                    .where(RELEASE_TRAIN.TRAIN_VERSION.eq(target.ref))
                    .fetchOne()
                    ?.let { AccreditationCheck(checkNotNull(it.needsReaccreditation), it.alertRef, it.taskRef) }
            }

            ReleaseTarget.Kind.PACK -> {
                dsl
                    .selectFrom(PACK_RELEASE_RECORD)
                    .where(PACK_RELEASE_RECORD.PACK_REF.eq(target.ref))
                    .fetchOne()
                    ?.let { AccreditationCheck(checkNotNull(it.needsReaccreditation), it.alertRef, it.taskRef) }
            }
        }

    override fun link(
        target: ReleaseTarget,
        check: AccreditationCheck,
    ) {
        val updated =
            when (target.kind) {
                ReleaseTarget.Kind.TRAIN -> {
                    dsl
                        .update(RELEASE_TRAIN)
                        .set(RELEASE_TRAIN.NEEDS_REACCREDITATION, check.assessmentNeeded)
                        .set(RELEASE_TRAIN.ALERT_REF, check.alertRef)
                        .set(RELEASE_TRAIN.TASK_REF, check.taskRef)
                        .where(RELEASE_TRAIN.TRAIN_VERSION.eq(target.ref))
                        .execute()
                }

                ReleaseTarget.Kind.PACK -> {
                    dsl
                        .update(PACK_RELEASE_RECORD)
                        .set(PACK_RELEASE_RECORD.NEEDS_REACCREDITATION, check.assessmentNeeded)
                        .set(PACK_RELEASE_RECORD.ALERT_REF, check.alertRef)
                        .set(PACK_RELEASE_RECORD.TASK_REF, check.taskRef)
                        .where(PACK_RELEASE_RECORD.PACK_REF.eq(target.ref))
                        .execute()
                }
            }
        check(updated == 1) { "the release record of ${target.kind} ${target.ref} is gone" }
    }
}
