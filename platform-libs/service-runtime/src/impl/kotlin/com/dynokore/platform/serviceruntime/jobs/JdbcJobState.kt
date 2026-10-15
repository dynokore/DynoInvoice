package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.tenancykernel.SqlIdentifier
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** One `job_run` row's key (INV-Q-2948, INV-Q-3039, INV-Q-3034). */
internal data class RunKey(
    val job: JobDefinition,
    val target: JobTarget,
    val tick: Instant,
    val trigger: JobTrigger,
    val attempt: Int,
)

internal data class RunRow(
    val attempt: Int,
    val status: JobRunStatus,
    val startedAt: Instant,
)

internal data class PauseRow(
    val pausedAt: Instant,
    val until: Instant,
)

/**
 * `job_run` and `job_pause` statements as jOOQ DSL on the DSLContext of tenancy-jooq's factory, run in the caller's
 * open transaction, in which the target's tenant is bound (ARC-21; forced RLS, CS-19). Every statement also names
 * `tenant_id` (defence in depth, TPL-11; tenancy-jooq checks the predicate again). Identifiers are `DSL.name` of the
 * [SqlIdentifier]-validated schema and constant column names; every value is a bind value.
 */
internal class JdbcJobState(
    private val dsl: DSLContext,
    schema: String,
    private val service: String,
) {
    private val run: Table<*> = DSL.table(DSL.name(SqlIdentifier.require(schema, "schema"), JobSchema.JOB_RUN))
    private val pause: Table<*> = DSL.table(DSL.name(SqlIdentifier.require(schema, "schema"), JobSchema.JOB_PAUSE))

    private val tenantId: Field<UUID> = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
    private val serviceName: Field<String> = DSL.field(DSL.name("service"), SQLDataType.CLOB)
    private val jobName: Field<String> = DSL.field(DSL.name("job"), SQLDataType.CLOB)
    private val entityRef: Field<String> = DSL.field(DSL.name("entity_ref"), SQLDataType.CLOB)
    private val tickAt: Field<OffsetDateTime> = DSL.field(DSL.name("tick"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    private val trigger: Field<String> = DSL.field(DSL.name("trigger"), SQLDataType.CLOB)
    private val attempt: Field<Int> = DSL.field(DSL.name("attempt"), SQLDataType.INTEGER)
    private val status: Field<String> = DSL.field(DSL.name("status"), SQLDataType.CLOB)
    private val reason: Field<String> = DSL.field(DSL.name("reason"), SQLDataType.CLOB)
    private val startedAt: Field<OffsetDateTime> = DSL.field(DSL.name("started_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    private val finishedAt: Field<OffsetDateTime> =
        DSL.field(DSL.name("finished_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    private val durationMillis: Field<Long> = DSL.field(DSL.name("duration_millis"), SQLDataType.BIGINT)
    private val pausedAt: Field<OffsetDateTime> = DSL.field(DSL.name("paused_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    private val pausedUntil: Field<OffsetDateTime> =
        DSL.field(DSL.name("paused_until"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    private val endedAt: Field<OffsetDateTime> = DSL.field(DSL.name("ended_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)

    /** Inserts the run IN_PROGRESS; false when the key already has a row (another replica or request has it). */
    fun begin(
        key: RunKey,
        startedAt: Instant,
    ): Boolean =
        dsl
            .insertInto(run)
            .columns(tenantId, serviceName, jobName, entityRef, tickAt, trigger, attempt, status, this.startedAt)
            .values(
                key.target.tenant.value,
                service,
                key.job.name,
                key.target.entity.value,
                at(key.tick),
                key.trigger.name,
                key.attempt,
                JobRunStatus.IN_PROGRESS.name,
                at(startedAt),
            ).onConflictDoNothing()
            .execute() == 1

    /**
     * Completes an IN_PROGRESS run; false when it is no longer in progress (the sweeper or another finish got
     * there).
     */
    fun finish(
        key: RunKey,
        failureReason: String?,
        finishedAt: Instant,
        durationMillis: Long,
    ): Boolean =
        dsl
            .update(run)
            .set(status, if (failureReason == null) JobRunStatus.SUCCEEDED.name else JobRunStatus.FAILED.name)
            .set(reason, failureReason)
            .set(this.finishedAt, at(finishedAt))
            .set(this.durationMillis, durationMillis)
            .where(keyCondition(key.job, key.target, key.tick, key.trigger).and(attempt.eq(key.attempt)))
            .and(status.eq(JobRunStatus.IN_PROGRESS.name))
            .execute() == 1

    /** The latest attempt for (job, target, tick, trigger), or null. */
    fun latest(
        job: JobDefinition,
        target: JobTarget,
        tick: Instant,
        trigger: JobTrigger,
    ): RunRow? =
        dsl
            .select(attempt, status, startedAt)
            .from(run)
            .where(keyCondition(job, target, tick, trigger))
            .orderBy(attempt.desc())
            .limit(1)
            .fetchOne { row ->
                RunRow(
                    row.get(attempt),
                    JobRunStatus.valueOf(row.get(status)),
                    row.get(startedAt).toInstant(),
                )
            }

    /** The open pause row of (job, target), locked for this transaction, or null. */
    fun openPause(
        job: JobDefinition,
        target: JobTarget,
    ): PauseRow? =
        dsl
            .select(pausedAt, pausedUntil)
            .from(pause)
            .where(targetCondition(job, target))
            .and(endedAt.isNull)
            .forUpdate()
            .fetchOne { row -> PauseRow(row.get(pausedAt).toInstant(), row.get(pausedUntil).toInstant()) }

    /** Opens a pause; false when (job, target) already has an open one (a concurrent request). */
    fun openNewPause(
        job: JobDefinition,
        target: JobTarget,
        pausedAt: Instant,
        until: Instant,
    ): Boolean =
        dsl
            .insertInto(pause)
            .columns(tenantId, serviceName, jobName, entityRef, this.pausedAt, pausedUntil)
            .values(target.tenant.value, service, job.name, target.entity.value, at(pausedAt), at(until))
            .onConflict(tenantId, serviceName, jobName, entityRef)
            .where(endedAt.isNull)
            .doNothing()
            .execute() == 1

    fun replaceUntil(
        job: JobDefinition,
        target: JobTarget,
        until: Instant,
    ) {
        dsl
            .update(pause)
            .set(pausedUntil, at(until))
            .where(openCondition(job, target))
            .execute()
    }

    /** Ends the open pause at [endedAt]: resumed, lapsed at the deadline bound, or past its end time. */
    fun endPause(
        job: JobDefinition,
        target: JobTarget,
        endedAt: Instant,
    ) {
        dsl
            .update(pause)
            .set(this.endedAt, at(endedAt))
            .where(openCondition(job, target))
            .execute()
    }

    private fun openCondition(
        job: JobDefinition,
        target: JobTarget,
    ) = targetCondition(job, target).and(endedAt.isNull)

    private fun targetCondition(
        job: JobDefinition,
        target: JobTarget,
    ) = tenantId
        .eq(target.tenant.value)
        .and(serviceName.eq(service))
        .and(jobName.eq(job.name))
        .and(entityRef.eq(target.entity.value))

    private fun keyCondition(
        job: JobDefinition,
        target: JobTarget,
        tick: Instant,
        trigger: JobTrigger,
    ) = targetCondition(job, target).and(tickAt.eq(at(tick))).and(this.trigger.eq(trigger.name))

    private fun at(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
}
