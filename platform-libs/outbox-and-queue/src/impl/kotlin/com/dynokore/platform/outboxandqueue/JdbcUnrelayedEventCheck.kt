package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant
import java.util.BitSet

/**
 * [UnrelayedEventCheck] for one service schema (owner rulings INV-Q-4334 (B) and INV-Q-4350 (A); coordinator ruling
 * INV-Q-4403 (A)). It finds every outbox row whose `published_at` is set but whose event id is not in its topic in the
 * event log.
 *
 * - **Outbox side.** Every run reads the whole outbox across tenants, each page in a transaction under the platform
 *   binding, through the `<svc>_sys`-owned function [OutboxSchema.OUTBOX_CLAIM_PAGE] (`seq`, `tenant_id`,
 *   `published_at` of the claim view's rows; INV-Q-5612), in keyset pages of [pageSize] rows on `seq` (the view's key; it exposes no event id), each page verified
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
    private val scope: TenantScope,
    private val dsl: DSLContext,
    schema: String,
    private val service: String,
    private val presence: EventLogPresence,
    private val settings: UnrelayedEventSettings,
    private val sink: UnrelayedEventSink,
    private val pageSize: Int = DEFAULT_PAGE,
) : UnrelayedEventCheck {
    private val schemaName = SqlIdentifier.require(schema, "schema")
    private val outbox: Table<*> = DSL.table(DSL.name(schemaName, OutboxSchema.OUTBOX))
    private val inbox: Table<*> = DSL.table(DSL.name(schemaName, OutboxSchema.INBOX))
    private var verified = SeqSet(0)

    init {
        require(service.isNotBlank()) { "service must not be blank" }
        require(pageSize in 1..MAX_PAGE) { "page size must be 1..$MAX_PAGE, was $pageSize" }
    }

    @Synchronized
    override fun check(): UnrelayedEventCheckResult {
        var stillVerified: SeqSet? = null
        val found = ArrayList<Published>()
        run {
            var after = 0L
            while (true) {
                val page = page(after)
                val published = page.filter { it.publishedAt != null }
                val kept = stillVerified ?: published.firstOrNull()?.let { SeqSet(it.seq) }?.also { stillVerified = it }
                val unverified =
                    published.filter { row ->
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

    private fun page(after: Long): List<Claimed> =
        scope.bindWorkload(TenantId.PLATFORM) {
            scope.transactions.inTransaction {
                val claim = OutboxClaimPage(schemaName).call(after, pageSize, false)
                dsl
                    .select(claim.seq, claim.tenantId, claim.publishedAt)
                    .from(claim)
                    .fetch {
                        Claimed(
                            it.get(claim.seq),
                            TenantId(it.get(claim.tenantId)),
                            it.get(claim.publishedAt)?.toInstant(),
                        )
                    }
            }
        }

    /** Records [row] as raised in the inbox, in its tenant's transaction; true only for the run that records it first. */
    private fun recordRaised(row: Published): Boolean =
        scope.bindWorkload(row.tenant) {
            scope.transactions.inTransaction {
                dsl
                    .insertInto(inbox)
                    .columns(CONSUMER, EVENT_ID, TENANT_ID)
                    .values(RAISED_CONSUMER, row.eventId, row.tenant.value)
                    .onConflictDoNothing()
                    .execute() == 1
            }
        }

    /** The topic and event id of each candidate, read once per tenant in a transaction bound to it. */
    private fun describe(candidates: List<Claimed>): List<Published> =
        candidates
            .groupBy { it.tenant }
            .flatMap { (tenant, ofTenant) ->
                ofTenant.chunked(pageSize).flatMap { chunk ->
                    val publishedAt = chunk.associate { it.seq to checkNotNull(it.publishedAt) }
                    scope.bindWorkload(tenant) {
                        scope.transactions.inTransaction {
                            dsl
                                .select(SEQ, TOPIC, ID)
                                .from(outbox)
                                .where(SEQ.`in`(publishedAt.keys).and(PUBLISHED_AT.isNotNull))
                                .fetch {
                                    val seq = checkNotNull(it.get(SEQ))
                                    Published(
                                        seq,
                                        tenant,
                                        checkNotNull(it.get(TOPIC)),
                                        checkNotNull(it.get(ID)),
                                        publishedAt.getValue(seq),
                                    )
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

        private val SEQ = DSL.field(DSL.name("seq"), SQLDataType.BIGINT)
        private val TOPIC = DSL.field(DSL.name("topic"), SQLDataType.CLOB)
        private val ID = DSL.field(DSL.name("id"), SQLDataType.CLOB)
        private val PUBLISHED_AT = DSL.field(DSL.name("published_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
        private val CONSUMER = DSL.field(DSL.name("consumer"), SQLDataType.CLOB)
        private val EVENT_ID = DSL.field(DSL.name("event_id"), SQLDataType.CLOB)
        private val TENANT_ID = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
    }
}
