package com.dynokore.gst.payload.adapters.persistence

import com.dynokore.gst.payload.adapters.persistence.jooq.tables.BuildJobState
import com.dynokore.gst.payload.application.build.BuildJobs
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.QueueEntry
import com.dynokore.platform.outboxandqueue.WorkQueue
import org.jooq.DSLContext
import java.util.UUID

/**
 * The build queue over the platform [WorkQueue], which has no dedupe key: the primary key of `payload.build_job_state`
 * is the key `build:<docUuid>:<version>`, and the job is enqueued only by the call that flips `job_enqueued`. Both
 * methods run in the caller's transaction.
 */
class JooqBuildJobs(
    private val dsl: DSLContext,
    private val queue: WorkQueue,
    private val boundTenant: () -> UUID,
) : BuildJobs {
    private val table = BuildJobState.BUILD_JOB_STATE

    /**
     * Inserts the state row of the version if absent, then locks it. A concurrent transaction that stores the other
     * fact of the same version waits here until this transaction commits, then sees what it stored.
     */
    fun lockVersion(
        docUuid: UUID,
        version: Int,
    ) {
        insertIfAbsent(docUuid, version)
        dsl
            .selectFrom(table)
            .where(key(docUuid, version))
            .forUpdate()
            .fetch()
    }

    override fun enqueueOnce(
        docUuid: UUID,
        version: Int,
    ): Boolean {
        insertIfAbsent(docUuid, version)
        val flipped =
            dsl
                .update(table)
                .set(table.JOB_ENQUEUED, true)
                .where(key(docUuid, version).and(table.JOB_ENQUEUED.eq(false)))
                .execute()
        if (flipped != 1) return false
        queue.enqueue(
            QueueEntry(docUuid.toString(), Lane.LIVE, """{"docUuid":"$docUuid","version":$version}"""),
        )
        return true
    }

    private fun insertIfAbsent(
        docUuid: UUID,
        version: Int,
    ) {
        dsl
            .insertInto(table)
            .set(table.TENANT_ID, boundTenant())
            .set(table.DOC_UUID, docUuid)
            .set(table.VERSION, version)
            .onConflictDoNothing()
            .execute()
    }

    private fun key(
        docUuid: UUID,
        version: Int,
    ) = table.TENANT_ID
        .eq(boundTenant())
        .and(table.DOC_UUID.eq(docUuid))
        .and(table.VERSION.eq(version))
}
