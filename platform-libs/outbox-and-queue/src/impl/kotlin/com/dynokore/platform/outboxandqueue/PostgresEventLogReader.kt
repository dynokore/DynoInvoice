package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant

/**
 * [EventLogReader] over the PostgreSQL event log: a consumer's read of its subscribed topics (INV-REQ-1205 b3), on the
 * service's runtime login (the pool of [eventLog]) and nothing else, as jOOQ on [dsl]: the topic tables,
 * `eventlog.claim`
 * and the purge watermark declared in [EventLogSchema.jooqTables] (INV-T-5306).
 *
 * - The records after an offset are found across tenants only through `eventlog.claim` (owned by `eventlog_sys`,
 *   [EventLogSchema.CLAIM]), which returns `log_offset`, `tenant_id` and `inserted_at` of the topics the login
 * subscribes
 *   to, and nothing for any other topic.
 * - The envelopes of one claimed batch are read once per tenant through [eventLog], each in a transaction that binds
 * that
 *   tenant and the service's workload principal first (tenancy-kernel, ARC-21; `TenantId.PLATFORM` for platform events,
 *   INV-Q-2927), under the tenant policy, so no read can return another tenant's envelope.
 *
 * Records come back in `log_offset` order. If a claimed record is no longer readable when its envelope is read (a
 * retention purge removed it in between), the batch ends just before it, so no record after it is returned ahead
 * of it; the next read starts there again.
 *
 * Each read first reads the topic's purge watermark (INV-REQ-1205 b6, INV-Q-3249) in the same statement, so the same
 * snapshot (`claim` is STABLE), as the claim: when the caller's next position (`afterOffset + 1`) is at or below it,
 * records the caller has not consumed were purged, and [read] throws [RebuildRequired] instead of returning the next
 * surviving record. A topic never purged has no watermark row and reads as watermark 0.
 *
 * The [backlog] and the [head] are measured through `claim` only (coordinator ruling INV-Q-4264): the oldest unconsumed
 * record is one single-row claim after the position, and the head is found by a galloping search of single-row claims
 * (exponential, then binary) from the given position. The backlog count is the offset lag (head minus position), an
 * upper bound of the records unconsumed, since offsets of rolled-back inserts and purged rows count.
 */
class PostgresEventLogReader(
    private val eventLog: TenantScope,
    private val dsl: DSLContext,
) : EventLogReader,
    EventLogLag,
    EventLogHead {
    override fun read(
        topic: String,
        afterOffset: Long,
        limit: Int,
    ): List<LogRecord> {
        val topicTable = DSL.table(DSL.name(EventLogSchema.SCHEMA, EventLogSchema.tableName(topic)))
        try {
            val (watermark, claimed) = eventLog.platformTransaction { claimBatch(topic, afterOffset, limit) }
            if (afterOffset + 1 <= watermark) throw RebuildRequired(topic, afterOffset + 1, watermark)
            val envelopes = HashMap<Long, String>()
            claimed.groupBy({ it.second }, { it.first }).forEach { (tenant, offsets) ->
                // a connection of its own even when the caller holds a transaction of another tenant
                eventLog.transactions.outsideOpenTransaction {
                    eventLog.bindWorkload(tenant) {
                        eventLog.transactions.inTransaction {
                            dsl
                                .select(LOG_OFFSET, ENVELOPE)
                                .from(topicTable)
                                .where(
                                    LOG_OFFSET.eq(
                                        DSL.any(DSL.`val`(offsets.toTypedArray(), SQLDataType.BIGINT.array())),
                                    ),
                                ).fetch()
                                .forEach { row -> envelopes[row.get(LOG_OFFSET)] = row.get(ENVELOPE).data() }
                        }
                    }
                }
            }
            return claimed
                .asSequence()
                .map { (offset, _) -> envelopes[offset]?.let { LogRecord(offset, it) } }
                .takeWhile { it != null }
                .filterNotNull()
                .toList()
        } catch (expected: Exception) {
            throw unreadable(topic, expected)
        }
    }

    override fun backlog(
        topic: String,
        afterOffset: Long,
    ): LogBacklog {
        try {
            return eventLog.platformTransaction {
                val oldest = next(topic, afterOffset) ?: return@platformTransaction LogBacklog(0, null)
                val head = gallop(topic, afterOffset, oldest.first)
                LogBacklog(head - afterOffset, oldest.second)
            }
        } catch (expected: Exception) {
            throw unreadable(topic, expected)
        }
    }

    override fun head(
        topic: String,
        from: Long,
    ): TopicHead {
        require(from >= 0) { "a head search starts at an offset of at least 0, was $from" }
        try {
            return eventLog.platformTransaction {
                val watermark = watermark(topic)
                val start = maxOf(from, watermark)
                val first = next(topic, start)
                val head = if (first == null) start else gallop(topic, start, first.first)
                TopicHead(head, watermark)
            }
        } catch (expected: Exception) {
            throw unreadable(topic, expected)
        }
    }

    /**
     * The failure as the reader reports it: a SQLSTATE found in [failure]'s cause chain (jOOQ wraps the SQLException,
     * so the runner reads it, INV-Q-5121) makes an [EventBindingUnavailable]; any other failure (a [RebuildRequired],
     * a lane refusal) is returned as it is.
     */
    private fun unreadable(
        topic: String,
        failure: Exception,
    ): Exception {
        val state = eventLog.sqlErrors.sqlStateOf(failure) ?: return failure
        return EventBindingUnavailable("event log unreadable for $topic: $state", failure)
    }

    /**
     * The highest offset present, given [present], an offset present after [from]: doubles a step from [present] until
     * a
     * single-row claim after `present + step` finds nothing, then halves the interval between the highest offset found
     * and that bound. About two claims per doubling of the distance from [from] to the head.
     */
    private fun gallop(
        topic: String,
        from: Long,
        present: Long,
    ): Long {
        require(present > from) { "the offset found lies after the start" }
        var low = present // present on the topic
        var step = 1L
        var high: Long // nothing present after it, as far as the search has seen
        while (true) {
            val probe = if (low > Long.MAX_VALUE - step) Long.MAX_VALUE else low + step
            val found = next(topic, probe)?.first
            if (found == null) {
                high = probe
                break
            }
            low = found
            step = if (step > Long.MAX_VALUE / 2) Long.MAX_VALUE else step * 2
        }
        while (low < high) {
            val middle = low + (high - low) / 2
            val found = next(topic, middle)?.first
            if (found == null) {
                high = middle
            } else {
                low = found
                if (found > high) high = found // inserted while searching: the head is at least that
            }
        }
        return low
    }

    /** The first record after [afterOffset] as `claim` returns it: (log_offset, inserted_at), or null when none. */
    private fun next(
        topic: String,
        afterOffset: Long,
    ): Pair<Long, Instant>? {
        val claim = EventLogClaim().call(topic, afterOffset, 1)
        return dsl
            .select(claim.logOffset, claim.insertedAt)
            .from(claim)
            .fetchOne()
            ?.let { it.get(claim.logOffset) to it.get(claim.insertedAt).toInstant() }
    }

    private fun watermark(topic: String): Long = dsl.select(watermarkOf(topic)).fetchSingle().value1()

    /** The topic's watermark and the claimed (offset, tenant) pairs after [afterOffset], read in one snapshot. */
    private fun claimBatch(
        topic: String,
        afterOffset: Long,
        limit: Int,
    ): Pair<Long, List<Pair<Long, TenantId>>> {
        val claim = EventLogClaim().call(topic, afterOffset, limit)
        val mark = watermarkOf(topic).`as`("watermark")
        val marked = dsl.select(mark).asTable(DSL.name("w"))
        val rows =
            dsl
                .select(marked.field(mark), claim.logOffset, claim.tenantId)
                .from(marked)
                .leftJoin(claim)
                .on(DSL.trueCondition())
                .orderBy(claim.logOffset)
                .fetch()
        var watermark = 0L
        val claimed =
            buildList {
                for (row in rows) {
                    watermark = checkNotNull(row.get(0, Long::class.java))
                    val offset = row.get(claim.logOffset)
                    if (offset != null) add(offset to TenantId(checkNotNull(row.get(claim.tenantId))))
                }
            }
        return watermark to claimed
    }

    /** `coalesce(max(watermark), 0)` of the topic's rows in the purge watermark table. */
    private fun watermarkOf(topic: String) =
        dsl
            .select(DSL.coalesce(DSL.max(WATERMARK), 0L))
            .from(DSL.table(DSL.name(EventLogSchema.SCHEMA, EventLogSchema.PURGE_WATERMARK)))
            .where(TOPIC_KEY.eq(EventLogSchema.tableName(topic)))
            .asField<Long>()

    private companion object {
        val LOG_OFFSET = DSL.field(DSL.name("log_offset"), SQLDataType.BIGINT)
        val ENVELOPE = DSL.field(DSL.name("envelope"), SQLDataType.JSONB)
        val WATERMARK = DSL.field(DSL.name("watermark"), SQLDataType.BIGINT)
        val TOPIC_KEY = DSL.field(DSL.name("topic_key"), SQLDataType.CLOB)
    }
}
