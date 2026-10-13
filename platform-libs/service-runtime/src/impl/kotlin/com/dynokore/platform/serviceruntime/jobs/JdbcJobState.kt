package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.SqlIdentifier
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant

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
 * `job_run` and `job_pause` statements, run on the connection of the caller's open transaction, in which
 * the target's tenant is bound (ARC-21; forced RLS, CS-19). Every statement also names `tenant_id`
 * (defence in depth, TPL-11).
 */
internal class JdbcJobState(
    private val connections: CurrentConnection,
    schema: String,
    private val service: String,
) {
    private val run = "${SqlIdentifier.require(schema, "schema")}.${JobSchema.JOB_RUN}"
    private val pause = "${SqlIdentifier.require(schema, "schema")}.${JobSchema.JOB_PAUSE}"

    /** Inserts the run IN_PROGRESS; false when the key already has a row (another replica or request has it). */
    fun begin(
        key: RunKey,
        startedAt: Instant,
    ): Boolean =
        connections
            .require()
            .prepareStatement(
                """
                insert into $run (tenant_id, service, job, entity_ref, tick, trigger, attempt, status, started_at)
                values (?, ?, ?, ?, ?, ?, ?, 'IN_PROGRESS', ?) on conflict do nothing
                """.trimIndent(),
            ).use {
                bindKey(it, key)
                it.setTimestamp(8, Timestamp.from(startedAt))
                it.executeUpdate() == 1
            }

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
        connections
            .require()
            .prepareStatement(
                """
                update $run set status = ?, reason = ?, finished_at = ?, duration_millis = ?
                where tenant_id = ? and service = ? and job = ? and entity_ref = ? and tick = ? and trigger = ? and attempt = ?
                  and status = 'IN_PROGRESS'
                """.trimIndent(),
            ).use {
                it.setString(1, if (failureReason == null) JobRunStatus.SUCCEEDED.name else JobRunStatus.FAILED.name)
                it.setString(2, failureReason)
                it.setTimestamp(3, Timestamp.from(finishedAt))
                it.setLong(4, durationMillis)
                bindKey(it, key, offset = 4)
                it.executeUpdate() == 1
            }

    /** The latest attempt for (job, target, tick, trigger), or null. */
    fun latest(
        job: JobDefinition,
        target: JobTarget,
        tick: Instant,
        trigger: JobTrigger,
    ): RunRow? =
        connections
            .require()
            .prepareStatement(
                """
                select attempt, status, started_at from $run
                where tenant_id = ? and service = ? and job = ? and entity_ref = ? and tick = ? and trigger = ?
                order by attempt desc limit 1
                """.trimIndent(),
            ).use {
                bindKey(it, RunKey(job, target, tick, trigger, 0), withAttempt = false)
                it.executeQuery().use { rows ->
                    if (!rows.next()) {
                        null
                    } else {
                        RunRow(
                            rows.getInt(1),
                            JobRunStatus.valueOf(rows.getString(2)),
                            rows.getTimestamp(3).toInstant(),
                        )
                    }
                }
            }

    /** The open pause row of (job, target), locked for this transaction, or null. */
    fun openPause(
        job: JobDefinition,
        target: JobTarget,
    ): PauseRow? =
        connections
            .require()
            .prepareStatement(
                """
                select paused_at, paused_until from $pause
                where tenant_id = ? and service = ? and job = ? and entity_ref = ? and ended_at is null for update
                """.trimIndent(),
            ).use {
                bindTarget(it, job, target)
                it.executeQuery().use { rows ->
                    if (!rows.next()) {
                        null
                    } else {
                        PauseRow(
                            rows.getTimestamp(1).toInstant(),
                            rows.getTimestamp(2).toInstant(),
                        )
                    }
                }
            }

    /** Opens a pause; false when (job, target) already has an open one (a concurrent request). */
    fun openNewPause(
        job: JobDefinition,
        target: JobTarget,
        pausedAt: Instant,
        until: Instant,
    ): Boolean =
        connections
            .require()
            .prepareStatement(
                """
                insert into $pause (tenant_id, service, job, entity_ref, paused_at, paused_until) values (?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, service, job, entity_ref) where ended_at is null do nothing
                """.trimIndent(),
            ).use {
                bindTarget(it, job, target)
                it.setTimestamp(5, Timestamp.from(pausedAt))
                it.setTimestamp(6, Timestamp.from(until))
                it.executeUpdate() == 1
            }

    fun replaceUntil(
        job: JobDefinition,
        target: JobTarget,
        until: Instant,
    ) = updateOpen(job, target, PAUSED_UNTIL, until)

    /** Ends the open pause at [endedAt]: resumed, lapsed at the deadline bound, or past its end time. */
    fun endPause(
        job: JobDefinition,
        target: JobTarget,
        endedAt: Instant,
    ) = updateOpen(job, target, ENDED_AT, endedAt)

    private fun updateOpen(
        job: JobDefinition,
        target: JobTarget,
        column: String,
        value: Instant,
    ) {
        val set = SqlIdentifier.require(column, "job_pause column")
        val connection: Connection = connections.require()
        connection
            .prepareStatement(
                "update $pause set $set = ? where tenant_id = ? and service = ? and job = ? and entity_ref = ? and ended_at is null",
            ).use {
                it.setTimestamp(1, Timestamp.from(value))
                bindTarget(it, job, target, offset = 1)
                it.executeUpdate()
            }
    }

    private fun bindTarget(
        statement: PreparedStatement,
        job: JobDefinition,
        target: JobTarget,
        offset: Int = 0,
    ) {
        statement.setObject(offset + 1, target.tenant.value)
        statement.setString(offset + 2, service)
        statement.setString(offset + 3, job.name)
        statement.setString(offset + 4, target.entity.value)
    }

    private fun bindKey(
        statement: PreparedStatement,
        key: RunKey,
        offset: Int = 0,
        withAttempt: Boolean = true,
    ) {
        bindTarget(statement, key.job, key.target, offset)
        statement.setTimestamp(offset + 5, Timestamp.from(key.tick))
        statement.setString(offset + 6, key.trigger.name)
        if (withAttempt) statement.setInt(offset + 7, key.attempt)
    }

    private companion object {
        const val PAUSED_UNTIL = "paused_until"
        const val ENDED_AT = "ended_at"
    }
}
