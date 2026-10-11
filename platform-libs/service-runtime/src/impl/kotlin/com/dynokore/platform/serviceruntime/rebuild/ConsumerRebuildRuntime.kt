package com.dynokore.platform.serviceruntime.rebuild

import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventLogHead
import com.dynokore.platform.outboxandqueue.JdbcConsumerReplay
import com.dynokore.platform.outboxandqueue.MovedPosition
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.PendingReplay
import com.dynokore.platform.outboxandqueue.ReadModelClear
import com.dynokore.platform.outboxandqueue.ReplayJson
import com.dynokore.platform.outboxandqueue.ReplayMode
import com.dynokore.platform.outboxandqueue.ReplayRequest
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.ObjectMapper
import java.sql.SQLException
import java.time.Clock
import java.util.UUID

/**
 * rebuildConsumer of one consuming service (INV-API-3656; owner ruling INV-Q-3599; rulings INV-Q-3663, INV-Q-3665,
 * INV-Q-3667, INV-Q-3671). Checks, in this order: the topic is one of [subscriptions] (EVL-001); the caller holds the
 * platform operator role (EVL-002); then, in one transaction under the platform tenant: a pending replay for the
 * (consumer, producer) pair is returned when its mode and scope are the same (200, nothing written) and refused
 * otherwise (EVL-004); the consumer must hold the topic in 'rebuild required' (EVL-003); then the head move, the
 * `ReplayRequested` ([JdbcConsumerReplay]) and the `AuditEntryRecorded` are written and commit together.
 *
 * A full sync (coordinator rulings INV-Q-3603, INV-Q-3786) runs the same steps in one transaction on the consumer's
 * worker-role connection bound to the platform tenant ([FullSyncWiring]): the service's [ReadModelClear] for the
 * producer, the delete of its docsequence rows for that producer across every tenant (the inbox is kept), the move of
 * every topic of that producer the consumer reads, the request and the audit entry.
 *
 * A topic with several catalogue producers is addressed to all of them by one call (owner ruling INV-Q-3788 A): one
 * head move, one `ReplayRequested` per producer under one replay id; the repeat rule and EVL-004 hold over that set of
 * pairs, and the response lists every addressee in `producers` with producer 'several producers'. A full sync moves
 * every topic of its producers the consumer reads, so when one of those topics has other producers too they are
 * addressed as well, until the set is closed (no moved topic has a producer left out): the clear of every addressee
 * runs and, addressed to several producers, no docsequence row is deleted (coordinator ruling on INV-Q-3788).
 *
 * Wiring: [scope] is the service's tenant binding, transactions and current connection (app role); [outbox] and
 * [audit] the service's outbox and audit writers; [head] the consumer's event-log claim path
 * (`PostgresEventLogReader`).
 */
class ConsumerRebuildRuntime
@JvmOverloads
constructor(
    private val service: String,
    private val schema: String,
    private val catalogue: EventCatalogue,
    private val subscriptions: List<ConsumerSubscription>,
    scope: TenantScope,
    outbox: OutboxWriter,
    audit: AuditWriter,
    private val head: EventLogHead,
    private val authorizer: ConsumerRebuildAuthorizer,
    private val clock: Clock,
    private val fullSync: FullSyncWiring? = null,
) : ConsumerRebuildCommands {
    /** The transaction a request runs in: the app role's for a replay by scope, the worker's for a full sync. */
    private inner class Side(
        val scope: TenantScope,
        outbox: OutboxWriter,
        val audit: AuditWriter,
    ) {
        val replay = JdbcConsumerReplay(schema, scope.connections, head, outbox, clock)

        fun <T> platformTransaction(block: () -> T): T = scope.bind(TenantId.PLATFORM) { scope.transactions.inTransaction(block) }
    }

    private val appSide = Side(scope, outbox, audit)
    private val fullSyncSide = fullSync?.let { Side(it.worker, it.outbox, it.audit) }
    private val json = ReplayJson()
    private val mapper = ObjectMapper()

    override fun rebuildConsumer(request: RebuildConsumerRequest): RebuildOutcome {
        val subscription =
            subscriptions.firstOrNull { it.topic == request.topic }
                ?: return RebuildOutcome.Refused(RebuildErrorCode.TOPIC_NOT_CONSUMED)
        val entry =
            catalogue.topic(request.topic) ?: return RebuildOutcome.Refused(RebuildErrorCode.TOPIC_NOT_CONSUMED)
        if (!authorizer.isPlatformOperator(request.actor)) return RebuildOutcome.Refused(RebuildErrorCode.ROLE_NOT_PERMITTED)
        val consumer = subscription.consumer
        val (producers, side) =
            if (request.mode == ReplayMode.FULL_SYNC) {
                val closed = fullSyncAddressees(consumer, entry.producers)
                check(fullSync?.clears?.map { it.producer }?.containsAll(closed) == true) {
                    "a full sync from $closed needs the service's read-model clear for each (ReadModelClear)"
                }
                closed to checkNotNull(fullSyncSide) { "full sync needs the worker-role wiring (FullSyncWiring)" }
            } else {
                entry.producers.sorted() to appSide
            }
        repeat(ATTEMPTS) {
            try {
                return side.platformTransaction { decide(side, request, consumer, producers) }
            } catch (race: SQLException) {
                // A concurrent call for the pair committed its pending replay first (unique index): read it again.
                if (race.sqlState != UNIQUE_VIOLATION) throw race
            }
        }
        error("rebuildConsumer kept racing on the pending replay")
    }

    /**
     * The addressees of a full sync of a topic produced by [start]: a full sync moves every topic of its producers that
     * [consumer] reads (INV-Q-3603), so each such topic's other producers are addressed too, until no moved topic has a
     * producer outside the set (owner ruling INV-Q-3788 A). Without this, a moved topic's other producers' history
     * would be skipped with no replay to fill it.
     */
    private fun fullSyncAddressees(
        consumer: String,
        start: Collection<String>,
    ): List<String> {
        val read = consumedEntries(consumer)
        val addressees = start.toMutableSet()
        do {
            val added = read.filter { e -> e.producers.any { it in addressees } }.flatMap { it.producers }.filter(addressees::add)
        } while (added.isNotEmpty())
        return addressees.sorted()
    }

    private fun consumedEntries(consumer: String) = subscriptions.filter { it.consumer == consumer }.mapNotNull { catalogue.topic(it.topic) }

    private fun decide(
        side: Side,
        request: RebuildConsumerRequest,
        consumer: String,
        producers: List<String>,
    ): RebuildOutcome {
        repeatOrConflict(side, request, consumer, producers)?.let { return it }
        if (!side.replay.isBehind(consumer, request.topic)) {
            return RebuildOutcome.Refused(RebuildErrorCode.NOT_REBUILD_REQUIRED)
        }
        val replayId = UUID.randomUUID()
        val requests = producers.map { ReplayRequest(replayId, it, request.mode, request.scope) }
        val moved =
            if (request.mode == ReplayMode.FULL_SYNC) {
                // Every topic of the addressees this consumer reads goes to the head (INV-Q-3603, INV-Q-3788 A).
                val topics = consumedEntries(consumer).filter { e -> e.producers.any { it in producers } }.map { it.topic }
                val clears = fullSync?.clears.orEmpty().filter { it.producer in producers }
                side.replay.fullSync(consumer, topics, requests, clears, request.correlation, request.causation)
            } else {
                side.replay.request(consumer, listOf(request.topic), requests, request.correlation, request.causation)
            }
        side.audit.record(auditEntry(request, producers, moved), context(replayId, request))
        val pending = side.replay.pending(consumer, producers.first()) ?: error("the pending replay was just written")
        return RebuildOutcome.Done(state(pending, request.topic, producers, repeat = false), created = true)
    }

    /**
     * The repeat rule over the addressed pairs (owner rulings INV-Q-3665, INV-Q-3788 A): none pending, `null` (go on);
     * the pending replay returned (200) when every pending pair holds one replay, of this mode and scope, addressed to
     * exactly [producers] (pairs of it already completed stay part of it until it closes); otherwise EVL-004.
     */
    private fun repeatOrConflict(
        side: Side,
        request: RebuildConsumerRequest,
        consumer: String,
        producers: List<String>,
    ): RebuildOutcome? {
        val pending = producers.mapNotNull { side.replay.pending(consumer, it) }
        val first = pending.firstOrNull() ?: return null
        val same =
            pending.all { it.replayId == first.replayId && it.mode == request.mode && it.scope == request.scope } &&
                side.replay.addressees(consumer, first.replayId) == producers
        return if (same) {
            RebuildOutcome.Done(state(first, request.topic, producers, repeat = true), created = false)
        } else {
            RebuildOutcome.Refused(RebuildErrorCode.OTHER_REPLAY_PENDING)
        }
    }

    private fun state(
        pending: PendingReplay,
        topic: String,
        producers: List<String>,
        repeat: Boolean,
    ) = ReplayState(
        replayId = pending.replayId,
        topic = if (topic in pending.topics) topic else pending.topics.first(),
        producer = ReplayState.producerOf(producers),
        producers = producers,
        mode = pending.mode,
        scope = pending.scope,
        requestedAt = pending.requestedAt,
        repeat = repeat,
    )

    /**
     * The audit entry of INV-Q-3663 (INV-REQ-2588 b9): actor; action rebuildConsumer; scope = consumer service, topic,
     * producer, every addressee (INV-Q-3788 A), mode and replay scope; reason = the caller's text, else the
     * Idempotency-Key; prior state = the positions before the move.
     */
    private fun auditEntry(
        request: RebuildConsumerRequest,
        producers: List<String>,
        moved: List<MovedPosition>,
    ): AuditEntry {
        val scopeNode = mapper.createObjectNode()
        scopeNode.put("consumerService", service)
        scopeNode.put("topic", request.topic)
        scopeNode.put("producer", ReplayState.producerOf(producers))
        producers.forEach(scopeNode.putArray("producers")::add)
        scopeNode.put("mode", request.mode.name)
        request.scope?.let { scopeNode.set("replayScope", mapper.readTree(json.writeScope(it))) }
        val prior = mapper.createObjectNode()
        val positions = prior.putArray("positions")
        moved.forEach { positions.addObject().put("topic", it.topic).put("nextOffset", it.before) }
        return AuditEntry(
            actor = request.actor,
            action = OPERATION,
            scope = mapper.writeValueAsString(scopeNode),
            reason = request.reason ?: "Idempotency-Key ${request.idempotencyKey}",
            priorStateJson = mapper.writeValueAsString(prior),
        )
    }

    private fun context(
        replayId: UUID,
        request: RebuildConsumerRequest,
    ) = EventContext(
        tenant = TenantId.PLATFORM,
        entity = EntityRef.PLATFORM,
        docUuid = null,
        transactionDate = null,
        aggregateKey = "$service/rebuild/$replayId",
        aggregateVersion = null,
        correlation = request.correlation,
        causation = request.causation,
    )

    private companion object {
        const val OPERATION = "rebuildConsumer"
        const val UNIQUE_VIOLATION = "23505"
        const val ATTEMPTS = 3
    }
}

/**
 * The worker-role side of a full sync (coordinator ruling INV-Q-3786): [worker] binds the platform tenant on the
 * consumer's worker-role connections (its `TransactionRunnerImpl` over the worker data source); [outbox] and [audit]
 * write through that same connection; [clears] holds the service's read-model clear per producer.
 */
class FullSyncWiring(
    val worker: TenantScope,
    val outbox: OutboxWriter,
    val audit: AuditWriter,
    val clears: List<ReadModelClear>,
) {
    init {
        require(clears.map { it.producer }.toSet().size == clears.size) { "one read-model clear per producer" }
    }
}
