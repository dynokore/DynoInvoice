package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.drain.DrainBacklog
import com.dynokore.gst.submission.application.drain.QueuedVersion
import com.dynokore.platform.outboxandqueue.PendingPartitionReader
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException

/**
 * [DrainBacklog] over the version rows of one tenant in state QUEUED whose document still has an unclaimed work-queue
 * item, INV-REQ-554 and INV-AC-20847. The unclaimed items are read through the platform library
 * ([PendingPartitionReader]); the partition key of a submission queue item is the document's Doc UUID
 * (SubmissionQueue),
 * so a version is listed only when its Doc UUID is a pending partition. This adapter names neither the queue table nor
 * its columns. The entity is the version row's, the period is that of the register entry of the same document, and the
 * queuing time is `queued_at` of the version row. The tenant is in every predicate as well as the row level security.
 * A read error, or a version without a queuing time or a register entry, returns null (fail closed); values are never
 * logged.
 */
class JooqDrainBacklog(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
    private val pending: PendingPartitionReader,
) : DrainBacklog {
    private val version = Submission.SUBMISSION_
    private val entry = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY

    override fun queued(tenant: TenantId): List<QueuedVersion>? =
        try {
            runner.inTransaction { read(tenant) }
        } catch (ignored: DataAccessException) {
            null
        } catch (ignored: IllegalStateException) {
            null
        }

    private fun read(tenant: TenantId): List<QueuedVersion> {
        val partitions = pending.pendingPartitionKeys().toSet()
        if (partitions.isEmpty()) return emptyList()
        return dsl
            .select(version.DOC_UUID, version.VERSION, version.ENTITY_REF, version.QUEUED_AT, entry.PERIOD_REF)
            .from(version)
            .join(entry)
            .on(entry.DOC_UUID.eq(version.DOC_UUID))
            .and(entry.TENANT_ID.eq(version.TENANT_ID))
            .where(version.TENANT_ID.eq(tenant.value))
            .and(version.STATE.eq(QUEUED))
            .and(version.DOC_UUID.`in`(partitions))
            .fetch()
            .map { row ->
                QueuedVersion(
                    docUuid = checkNotNull(row.get(version.DOC_UUID)),
                    version = checkNotNull(row.get(version.VERSION)),
                    entityRef = checkNotNull(row.get(version.ENTITY_REF)),
                    periodRef = checkNotNull(row.get(entry.PERIOD_REF)),
                    queuedAt = checkNotNull(row.get(version.QUEUED_AT)).toInstant(),
                )
            }.sortedWith(compareBy({ it.queuedAt }, { it.docUuid }, { it.version }))
    }

    private companion object {
        const val QUEUED = "QUEUED"
    }
}
