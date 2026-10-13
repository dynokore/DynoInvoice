package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * [UnrelayedEventCheck] for one service schema (owner rulings INV-Q-4334 (B) and INV-Q-4350 (A)). It finds every
 * outbox row whose `published_at` is set but whose event id is not in its topic in the event log.
 *
 * - **Outbox side.** The published rows are found across tenants on [claimSource], a connection of the service's
 *   runtime login, through the `<svc>_sys`-owned [OutboxSchema.OUTBOX_CLAIM_VIEW] (`seq`, `tenant_id`, `published_at`
 *   only); each row's topic and event id are then read in a transaction bound to its tenant ([TenantScope.bindWorkload]).
 * - **Event-log side.** The ids are sent per topic, at most [UnrelayedEventSettings.batchSize] at a time, to
 *   [EventLogPresence] (`eventlog.events_missing`), which returns the missing ones and the topic's retention cut-off.
 * - **Never a normal row.** The relay commits the event-log insert before it sets `published_at` ([JdbcOutboxRelay],
 *   [PostgresEventBinding]), so a relayed row is in the log until the event-log purge removes it. A missing id is
 *   reported only when the row was published more than [UnrelayedEventSettings.margin] after the cut-off, so an event
 *   purged by the event log is never reported (the margin covers the relay lag and the clock skew between instances).
 * - **Once per row.** A finding is raised to [sink] when first seen and stays open, counted in
 *   [UnrelayedEventCheckResult.open], until its row is purged from the outbox, its event appears or it leaves the
 *   window; it is not raised again while open.
 * - **Incremental.** A row checked and found in the log is not checked again: the check remembers the highest `seq`
 *   below which every row was published and checked. An open finding is checked again on every run. The memory is per
 *   instance, so a restart checks the whole outbox once more.
 */
class JdbcUnrelayedEventCheck(
    private val claimSource: DataSource,
    private val scope: TenantScope,
    schema: String,
    private val service: String,
    private val presence: EventLogPresence,
    private val settings: UnrelayedEventSettings,
    private val sink: UnrelayedEventSink,
) : UnrelayedEventCheck {
    private val outbox = "${SqlIdentifier.require(schema, "schema")}.${OutboxSchema.OUTBOX}"
    private val claimView = "${SqlIdentifier.require(schema, "schema")}.${OutboxSchema.OUTBOX_CLAIM_VIEW}"
    private var checkedThrough = 0L
    private val open = LinkedHashMap<Long, UnrelayedEvent>()

    init {
        require(service.isNotBlank()) { "service must not be blank" }
    }

    @Synchronized
    override fun check(): UnrelayedEventCheckResult {
        val (candidates, prefix) = claimSource.connection.use { candidates(it) }
        val rows = describe(candidates)
        val found = LinkedHashMap<Long, UnrelayedEvent>()
        rows.groupBy { it.topic }.forEach { (topic, ofTopic) ->
            ofTopic.chunked(settings.batchSize).forEach { batch ->
                val answer = presence.missing(topic, batch.map { it.eventId })
                val reportFrom = answer.cutoff.plus(settings.margin)
                batch
                    .filter { it.eventId in answer.missing && it.publishedAt.isAfter(reportFrom) }
                    .forEach { found[it.seq] = UnrelayedEvent(service, topic, it.eventId, it.seq, it.publishedAt) }
            }
        }
        found.values.filter { it.outboxSeq !in open }.forEach(sink::raise)
        open.clear()
        open.putAll(found)
        checkedThrough = prefix
        return UnrelayedEventCheckResult(open.values.toList())
    }

    /**
     * The published rows to check: every row after [checkedThrough] and every open finding; and the new prefix, the
     * highest `seq` up to which every row after [checkedThrough] is published.
     */
    private fun candidates(claim: Connection): Pair<List<Claimed>, Long> {
        val published = ArrayList<Claimed>()
        var prefix = checkedThrough
        var contiguous = true
        var after = checkedThrough
        while (true) {
            val page = page(claim, after)
            for (row in page) {
                if (row.publishedAt == null) {
                    contiguous = false
                } else {
                    published += row
                    if (contiguous) prefix = row.seq
                }
            }
            if (page.size < PAGE) break
            after = page.last().seq
        }
        val reopened = if (open.isEmpty()) emptyList() else reread(claim, open.keys.filter { it <= checkedThrough })
        return (reopened + published) to prefix
    }

    private fun page(
        claim: Connection,
        after: Long,
    ): List<Claimed> = claim.prepareStatement("select seq, tenant_id, published_at from $claimView where seq > ? order by seq limit ?").use {
        it.setLong(1, after)
        it.setInt(2, PAGE)
        it.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(Claimed(rows.getLong(1), TenantId(rows.getObject(2, UUID::class.java)), rows.getTimestamp(3)?.toInstant()))
                }
            }
        }
    }

    private fun reread(
        claim: Connection,
        seqs: List<Long>,
    ): List<Claimed> = if (seqs.isEmpty()) {
        emptyList()
    } else {
        claim.prepareStatement("select seq, tenant_id, published_at from $claimView where seq = any(?) and published_at is not null").use {
            it.setArray(1, claim.createArrayOf("bigint", seqs.toTypedArray()))
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(Claimed(rows.getLong(1), TenantId(rows.getObject(2, UUID::class.java)), rows.getTimestamp(3).toInstant()))
                    }
                }
            }
        }
    }

    /** The topic and event id of each candidate, read once per tenant in a transaction bound to it. */
    private fun describe(candidates: List<Claimed>): List<Published> = candidates
        .groupBy { it.tenant }
        .flatMap { (tenant, ofTenant) ->
            ofTenant.chunked(PAGE).flatMap { chunk ->
                val publishedAt = chunk.associate { it.seq to checkNotNull(it.publishedAt) }
                scope.bindWorkload(tenant) {
                    scope.transactions.inTransaction {
                        val connection = scope.connections.require()
                        connection.prepareStatement("select seq, topic, id from $outbox where seq = any(?) and published_at is not null").use {
                            it.setArray(1, connection.createArrayOf("bigint", publishedAt.keys.toTypedArray()))
                            it.executeQuery().use { rows ->
                                buildList {
                                    while (rows.next()) {
                                        val seq = rows.getLong(1)
                                        add(Published(seq, rows.getString(2), rows.getString(3), publishedAt.getValue(seq)))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    private class Claimed(
        val seq: Long,
        val tenant: TenantId,
        val publishedAt: Instant?,
    )

    private class Published(
        val seq: Long,
        val topic: String,
        val eventId: String,
        val publishedAt: Instant,
    )

    private companion object {
        const val PAGE = 1_000
    }
}
