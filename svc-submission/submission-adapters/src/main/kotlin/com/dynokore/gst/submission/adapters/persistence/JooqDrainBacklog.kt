package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.WorkQueue
import com.dynokore.gst.submission.application.drain.DrainBacklog
import com.dynokore.gst.submission.application.drain.QueuedVersion
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * [DrainBacklog] over the version rows of one tenant whose work-queue item is unclaimed (state PENDING, the rule of
 * the drain order assigner), INV-REQ-554 and INV-AC-20847. The item is matched to the version by the `docUuid` and
 * `version` members of its payload (the queue item contract of SubmissionQueue). The entity is the version row's,
 * the period is that of the register entry of the same document, and the queuing time is `queued_at` of the version
 * row, else the enqueue instant of the item: a recorded instant, never an invented one. The tenant is in every
 * predicate as well as the row level security. A read error, an item whose payload names no version, or a version
 * without a register entry returns null (fail closed); values are never logged.
 */
class JooqDrainBacklog(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
    private val mapper: ObjectMapper = ObjectMapper(),
) : DrainBacklog {
    private val version = Submission.SUBMISSION_
    private val entry = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
    private val queue = WorkQueue.WORK_QUEUE

    override fun queued(tenant: TenantId): List<QueuedVersion>? =
        try {
            runner.inTransaction { read(tenant) }
        } catch (ignored: DataAccessException) {
            null
        } catch (ignored: IllegalStateException) {
            null
        }

    private fun read(tenant: TenantId): List<QueuedVersion> {
        val items = unclaimedItems(tenant)
        val rows =
            dsl
                .select(version.DOC_UUID, version.VERSION, version.ENTITY_REF, version.QUEUED_AT, entry.PERIOD_REF)
                .from(version)
                .join(entry)
                .on(entry.DOC_UUID.eq(version.DOC_UUID))
                .and(entry.TENANT_ID.eq(version.TENANT_ID))
                .where(version.TENANT_ID.eq(tenant.value))
                .fetch()
        return rows
            .mapNotNull { row ->
                val key = checkNotNull(row.get(version.DOC_UUID)) to checkNotNull(row.get(version.VERSION))
                items[key]?.let { enqueued ->
                    QueuedVersion(
                        docUuid = key.first,
                        version = key.second,
                        entityRef = checkNotNull(row.get(version.ENTITY_REF)),
                        periodRef = checkNotNull(row.get(entry.PERIOD_REF)),
                        queuedAt = row.get(version.QUEUED_AT)?.toInstant() ?: enqueued,
                    )
                }
            }.sortedWith(compareBy({ it.queuedAt }, { it.docUuid }, { it.version }))
    }

    /** The earliest enqueue instant of the unclaimed items of the tenant per (docUuid, version). */
    private fun unclaimedItems(tenant: TenantId): Map<Pair<String, Int>, Instant> =
        dsl
            .select(queue.PAYLOAD, queue.ENQUEUED_AT)
            .from(queue)
            .where(queue.TENANT_ID.eq(tenant.value))
            .and(queue.STATE.eq(PENDING))
            .fetch()
            .map { item ->
                val node = mapper.readTree(checkNotNull(item.get(queue.PAYLOAD)).data())
                val doc = node.get("docUuid")
                val number = node.get("version")
                check(doc != null && doc.isString && number != null && number.isInt) { "queue item without a version" }
                (doc.asString() to number.asInt()) to checkNotNull(item.get(queue.ENQUEUED_AT)).toInstant()
            }.groupBy({ it.first }, { it.second })
            .mapValues { (_, instants) -> instants.min() }

    private companion object {
        const val PENDING = "PENDING"
    }
}
