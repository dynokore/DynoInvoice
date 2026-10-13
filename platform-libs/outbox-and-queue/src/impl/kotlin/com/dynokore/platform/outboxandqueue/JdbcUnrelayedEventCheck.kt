package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import java.sql.Connection
import java.time.Instant
import java.util.BitSet
import java.util.UUID
import javax.sql.DataSource

/**
 * [UnrelayedEventCheck] for one service schema (owner rulings INV-Q-4334 (B) and INV-Q-4350 (A); coordinator ruling
 * INV-Q-4403 (A)). It finds every outbox row whose `published_at` is set but whose event id is not in its topic in the
 * event log.
 *
 * - **Outbox side.** Every run reads the whole outbox across tenants on [claimSource], a connection of the service's
 *   runtime login, through the `<svc>_sys`-owned [OutboxSchema.OUTBOX_CLAIM_VIEW] (`seq`, `tenant_id`, `published_at`
 *   only), in keyset pages of [pageSize] rows on `seq` (the view's key; it exposes no event id), each page verified
 *   before the next is read: memory is bounded by one page, the open findings and one bit per verified seq. The topic and event id of each published row not yet verified are read in a transaction bound to its tenant
 *   ([TenantScope.bindWorkload]). No row is skipped by position: a row written or marked by a transaction still open
 *   during one run is invisible or unpublished then, so it is not verified and the next run reads it again.
 * - **Event-log side.** The ids are sent per topic, at most [UnrelayedEventSettings.batchSize] at a time, to
 *   [EventLogPresence] (`eventlog.events_missing`), which returns the missing ones and the topic's retention cut-off.
 *   A row whose event was found is remembered as verified (in memory, per instance) and not sent again: no login can
 *   delete an event-log row except the age-only purge, which removes it only before the cut-off.
 * - **Never a normal row.** The relay commits the event-log insert before it sets `published_at` ([JdbcOutboxRelay],
 *   [PostgresEventBinding]). A missing id is reported only when the row was published more than
 *   [UnrelayedEventSettings.margin] after the cut-off, so an event purged by the event log is never reported.
 * - **Once per row, durably.** A finding is raised to [sink] only by the run that first records it in the service's
 *   own `inbox` under the consumer name [RAISED_CONSUMER] (`insert ... on conflict do nothing`, the runtime login's
 *   existing INSERT, in the row's tenant transaction): one alert per row across instances and restarts. The record
 *   ages out with the inbox retention, which is longer than the outbox's content window, so it outlives the row.
 */
class JdbcUnrelayedEventCheck(
    private val claimSource: DataSource,
    private val scope: TenantScope,
    schema: String,
    private val service: String,
    private val presence: EventLogPresence,
    private val settings: UnrelayedEventSettings,
    private val sink: UnrelayedEventSink,
    private val pageSize: Int = DEFAULT_PAGE,
) : UnrelayedEventCheck {
    private val outbox = "${SqlIdentifier.require(schema, "schema")}.${OutboxSchema.OUTBOX}"
    private val inbox = "${SqlIdentifier.require(schema, "schema")}.${OutboxSchema.INBOX}"
    private val claimView = "${SqlIdentifier.require(schema, "schema")}.${OutboxSchema.OUTBOX_CLAIM_VIEW}"
    private var verified = SeqSet(0)

    init {
        require(service.isNotBlank()) { "service must not be blank" }
        require(pageSize in 1..MAX_PAGE) { "page size must be 1..$MAX_PAGE, was $pageSize" }
    }

    @Synchronized
    override fun check(): UnrelayedEventCheckResult {
        var stillVerified: SeqSet? = null
        val found = ArrayList<Published>()
        claimSource.connection.use { claim ->
            var after = 0L
            while (true) {
                val page = page(claim, after)
                val published = page.filter { it.publishedAt != null }
                val kept = stillVerified ?: published.firstOrNull()?.let { SeqSet(it.seq) }?.also { stillVerified = it }
                val unverified = published.filter { row ->
                    val known = row.seq in verified
                    if (known) checkNotNull(kept) += row.seq
                    !known
                }
                if (unverified.isNotEmpty()) verify(unverified, checkNotNull(kept), found)
                if (page.size < pageSize) break
                after = page.last().seq
            }
        }
        verified = stillVerified ?: SeqSet(0)
        val open = found.map { UnrelayedEvent(service, it.topic, it.eventId, it.seq, it.publishedAt) }
        found.zip(open).filter { (row, _) -> recordRaised(row) }.forEach { (_, finding) -> sink.raise(finding) }
        return UnrelayedEventCheckResult(open)
    }

    /**
     * Sends one page's unverified rows to the event log per topic, at most [UnrelayedEventSettings.batchSize] ids per
     * call; adds the rows found there to [kept] and the rows reportable past the cut-off plus the margin to [found].
     */
    private fun verify(
        unverified: List<Claimed>,
        kept: SeqSet,
        found: MutableList<Published>,
    ) {
        describe(unverified).groupBy { it.topic }.forEach { (topic, ofTopic) ->
            ofTopic.chunked(settings.batchSize).forEach { batch ->
                val answer = presence.missing(topic, batch.map { it.eventId })
                val reportFrom = answer.cutoff.plus(settings.margin)
                batch.forEach {
                    when {
                        it.eventId !in answer.missing -> kept += it.seq
                        it.publishedAt.isAfter(reportFrom) -> found += it
                    }
                }
            }
        }
    }

    private fun page(
        claim: Connection,
        after: Long,
    ): List<Claimed> = claim.prepareStatement("select seq, tenant_id, published_at from $claimView where seq > ? order by seq limit ?").use {
        it.setLong(1, after)
        it.setInt(2, pageSize)
        it.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(Claimed(rows.getLong(1), TenantId(rows.getObject(2, UUID::class.java)), rows.getTimestamp(3)?.toInstant()))
                }
            }
        }
    }

    /** Records [row] as raised in the inbox, in its tenant's transaction; true only for the run that records it first. */
    private fun recordRaised(row: Published): Boolean = scope.bindWorkload(row.tenant) {
        scope.transactions.inTransaction {
            val connection = scope.connections.require()
            connection.prepareStatement("insert into $inbox (consumer, event_id, tenant_id) values (?, ?, ?) on conflict do nothing").use {
                it.setString(1, RAISED_CONSUMER)
                it.setString(2, row.eventId)
                it.setObject(3, row.tenant.value)
                it.executeUpdate() == 1
            }
        }
    }

    /** The topic and event id of each candidate, read once per tenant in a transaction bound to it. */
    private fun describe(candidates: List<Claimed>): List<Published> = candidates
        .groupBy { it.tenant }
        .flatMap { (tenant, ofTenant) ->
            ofTenant.chunked(pageSize).flatMap { chunk ->
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
                                        add(Published(seq, tenant, rows.getString(2), rows.getString(3), publishedAt.getValue(seq)))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    /** A set of outbox seqs from [base] on (a run's published rows span less than 2^31 seqs). */
    private class SeqSet(
        private val base: Long,
    ) {
        private val bits = BitSet()

        operator fun contains(seq: Long): Boolean = seq >= base && bits.get(Math.toIntExact(seq - base))

        operator fun plusAssign(seq: Long) = bits.set(Math.toIntExact(seq - base))
    }

    private class Claimed(
        val seq: Long,
        val tenant: TenantId,
        val publishedAt: Instant?,
    )

    private class Published(
        val seq: Long,
        val tenant: TenantId,
        val topic: String,
        val eventId: String,
        val publishedAt: Instant,
    )

    companion object {
        /** The inbox consumer name under which a raised finding is recorded (no consumer has this name). */
        const val RAISED_CONSUMER = "outbox-unrelayed-event-check"
        const val DEFAULT_PAGE = 1_000
        const val MAX_PAGE = 10_000
    }
}
