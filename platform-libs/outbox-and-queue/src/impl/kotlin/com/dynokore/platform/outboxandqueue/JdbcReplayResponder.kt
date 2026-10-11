package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import java.sql.Connection
import java.time.Clock
import javax.sql.DataSource

/**
 * The producer side of the replay protocol (INV-REQ-1205 b4; rulings INV-Q-3599, INV-Q-3601, INV-Q-3625, INV-Q-3644,
 * INV-Q-3671, INV-Q-3676), run by every producing service over the request topics it reads
 * (`gst.<consumer>.replay-requested.v1`, under its consumer SELECT grant, INV-REQ-1205 b3):
 *
 * - each request is taken in log order, one at a time per consumer; a request addressed to another producer is
 *   acknowledged (inbox row and position) and changes nothing;
 * - a request addressed to this service (by name, or to 'every service' on a topic whose readers include 'every
 *   service') is answered on the pair's replay topic: for each [ReplayStateSource] whose type that topic carries, the
 *   states within the scope (TENANT, ENTITY or PERIOD; a full sync: everything) are written page by page, each page
 *   in its own transaction bound to its tenant, with fresh event ids and the states' own `docsequence`;
 * - last, in one transaction under the platform tenant, the request's inbox row, the position past it and the
 *   `ReplayCompleted` marker (replay id, events replayed) commit together, so the relay publishes the marker after
 *   every replayed event. A crash before that leaves the request unhandled: the next run reads it again and restarts the
 *   replay from the start under the same replay id; the consumer finds the re-sent states STALE.
 *
 * A request the service cannot answer (not a valid ReplayRequested, from another service than the topic's consumer, or
 * for a pair without a replay topic) is dead-lettered as a consumer's invalid record is, and the run moves on.
 *
 * @param claimSource the worker role: positions, dead letters, the per-topic advisory lock.
 */
class JdbcReplayResponder
@JvmOverloads
constructor(
    private val service: String,
    schema: String,
    private val catalogue: EventCatalogue,
    private val reader: EventLogReader,
    private val claimSource: DataSource,
    private val scope: TenantScope,
    private val writer: JdbcOutboxWriter,
    sources: Collection<ReplayStateSource>,
    private val sink: RebuildRequiredSink,
    private val clock: Clock = Clock.systemUTC(),
    private val pageSize: Int = DEFAULT_PAGE,
) {
    private val s = SqlIdentifier.require(schema, "schema")
    private val offsets = "$s.${OutboxSchema.CONSUMER_OFFSET}"
    private val sourcesByType = sources.associateBy { it.eventType }
    private val envelopes = EnvelopeJson()
    private val json = ReplayJson()
    private val stopped = mutableSetOf<String>()

    init {
        require(sourcesByType.size == sources.size) { "one state source per event type" }
        require(pageSize in 1..MAX_PAGE) { "page size must be 1..$MAX_PAGE" }
    }

    /** Reads every request topic this service reads and answers what it finds there. Returns the replays answered. */
    @Synchronized
    fun runOnce(): List<ReplayAnswer> = catalogue.requestTopicsReadBy(service).flatMap { topic ->
        claimSource.connection.use { lock ->
            if (!tryLock(lock, topic.topic)) return@use emptyList()
            try {
                drain(topic)
            } finally {
                unlock(lock, topic.topic)
            }
        }
    }

    private fun drain(topic: ReplayRequestTopic): List<ReplayAnswer> {
        val next = claimSource.connection.use { position(it, topic.topic) }
        val records =
            try {
                reader.read(topic.topic, next - 1, BATCH)
            } catch (behind: RebuildRequired) {
                if (stopped.add(topic.topic)) {
                    sink.raise(RebuildRequiredIncident(service, topic.topic, behind.nextOffset, behind.watermark, clock.instant()))
                }
                return emptyList()
            }
        stopped.remove(topic.topic)
        return records.mapNotNull { answer(topic, it) }
    }

    private fun answer(
        topic: ReplayRequestTopic,
        record: LogRecord,
    ): ReplayAnswer? {
        val envelope: Envelope
        val request: ReplayRequest
        try {
            envelope = envelopes.read(record.envelopeJson)
            if (envelope.type != ReplayProtocol.REPLAY_REQUESTED) throw ContractViolation("not a ReplayRequested")
            if (envelope.service != topic.consumer) throw ContractViolation("request not from the topic's consumer")
            request = json.readRequest(envelope.dataJson)
        } catch (violation: ContractViolation) {
            deadLetter(topic.topic, record, violation.message ?: "contract violation")
            return null
        }
        val addressed =
            request.producer == service ||
                (request.producer == CatalogueEntry.EVERY_SERVICE && CatalogueEntry.EVERY_SERVICE in topic.producers)
        if (!addressed) {
            finish(topic.topic, record, envelope) {}
            return null
        }
        val replay = catalogue.replayTopic(request.producer, topic.consumer)
        if (replay == null) {
            deadLetter(topic.topic, record, "no replay topic for (${request.producer}, ${topic.consumer})")
            return null
        }
        if (isDuplicate(envelope)) {
            finish(topic.topic, record, envelope) {}
            return null
        }
        val correlation = CorrelationId("replay:${request.replayId}")
        val causation = CausationId(envelope.id.value)
        var count = 0L
        for (source in replay.eventTypes.sorted().mapNotNull { sourcesByType[it] }) {
            val tenants = request.scope?.onlyTenant?.let(::listOf) ?: source.tenants()
            for (tenant in tenants) {
                var cursor: String? = null
                do {
                    val page =
                        inTenant(tenant) {
                            val page = source.page(request.scope, cursor, pageSize)
                            require(page.items.all { it.type == source.eventType }) { "a state source returns its own type" }
                            writer.appendReplayed(replay.topic, page.items, tenant, correlation, causation)
                            page
                        }
                    count += page.items.size
                    cursor = page.next
                } while (cursor != null)
            }
        }
        val written = finish(topic.topic, record, envelope) { writer.appendCompleted(replay.topic, request.replayId, count, correlation, causation) }
        return if (written) ReplayAnswer(request.replayId, topic.consumer, replay.topic, count) else null
    }

    /**
     * Under the platform tenant: the request's inbox row and the position past it, and [complete] (the marker) only
     * when this transaction is the one that takes the inbox row. False when another run had already handled it.
     */
    private fun finish(
        topic: String,
        record: LogRecord,
        envelope: Envelope,
        complete: () -> Unit,
    ): Boolean = inTenant(TenantId.PLATFORM) {
        val connection = scope.connections.require()
        if (record.offset < lockOffset(connection, topic)) return@inTenant false
        val claimed = claimInbox(connection, envelope)
        if (claimed) complete()
        advance(connection, topic, record.offset)
        claimed
    }

    private fun isDuplicate(envelope: Envelope): Boolean = inTenant(TenantId.PLATFORM) {
        scope.connections.require().prepareStatement(
            "select 1 from $s.${OutboxSchema.INBOX} where consumer = ? and event_id = ?",
        ).use {
            it.setString(1, service)
            it.setString(2, envelope.id.value)
            it.executeQuery().use { rows -> rows.next() }
        }
    }

    private fun <T> inTenant(
        tenant: TenantId,
        block: () -> T,
    ): T = scope.bind(tenant) { scope.transactions.inTransaction(block) }

    private fun claimInbox(
        connection: Connection,
        envelope: Envelope,
    ): Boolean = connection.prepareStatement(
        "insert into $s.${OutboxSchema.INBOX} (consumer, event_id, tenant_id) values (?, ?, ?) on conflict do nothing",
    ).use {
        it.setString(1, service)
        it.setString(2, envelope.id.value)
        it.setObject(3, envelope.tenant.value)
        it.executeUpdate() == 1
    }

    private fun position(
        connection: Connection,
        topic: String,
    ): Long = connection.prepareStatement("select next_offset from $offsets where consumer = ? and topic = ?").use {
        it.setString(1, service)
        it.setString(2, topic)
        it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else FIRST_OFFSET }
    }

    private fun lockOffset(
        connection: Connection,
        topic: String,
    ): Long {
        connection.prepareStatement(
            "insert into $offsets (consumer, topic, next_offset) values (?, ?, $FIRST_OFFSET) on conflict do nothing",
        ).use {
            it.setString(1, service)
            it.setString(2, topic)
            it.executeUpdate()
        }
        return connection.prepareStatement("select next_offset from $offsets where consumer = ? and topic = ? for update").use {
            it.setString(1, service)
            it.setString(2, topic)
            it.executeQuery().use { rows ->
                check(rows.next()) { "offset row missing" }
                rows.getLong(1)
            }
        }
    }

    private fun advance(
        connection: Connection,
        topic: String,
        offset: Long,
    ) {
        connection.prepareStatement("update $offsets set next_offset = ? where consumer = ? and topic = ?").use {
            it.setLong(1, offset + 1)
            it.setString(2, service)
            it.setString(3, topic)
            it.executeUpdate()
        }
    }

    /** Records the dead letter and moves the position past it in one worker-role transaction. */
    private fun deadLetter(
        topic: String,
        record: LogRecord,
        reason: String,
    ) {
        claimSource.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                "insert into $s.${OutboxSchema.DEAD_LETTER} (consumer, topic, log_offset, tenant_id, reason, raw) " +
                    "values (?, ?, ?, ?, ?, ?)",
            ).use {
                it.setString(1, service)
                it.setString(2, topic)
                it.setLong(3, record.offset)
                it.setObject(4, envelopes.tenantOf(record.envelopeJson)?.value, java.sql.Types.OTHER)
                it.setString(5, reason)
                it.setString(6, record.envelopeJson)
                it.executeUpdate()
            }
            connection.prepareStatement(
                "insert into $offsets (consumer, topic, next_offset) values (?, ?, ?) " +
                    "on conflict (consumer, topic) do update set next_offset = greatest($offsets.next_offset, excluded.next_offset)",
            ).use {
                it.setString(1, service)
                it.setString(2, topic)
                it.setLong(3, record.offset + 1)
                it.executeUpdate()
            }
            connection.commit()
        }
    }

    private fun tryLock(
        connection: Connection,
        topic: String,
    ): Boolean = connection.prepareStatement("select pg_try_advisory_lock(hashtext(?))").use {
        it.setString(1, "$s/replay-responder/$topic")
        it.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
    }

    private fun unlock(
        connection: Connection,
        topic: String,
    ) {
        connection.prepareStatement("select pg_advisory_unlock(hashtext(?))").use {
            it.setString(1, "$s/replay-responder/$topic")
            it.execute()
        }
    }

    private companion object {
        const val FIRST_OFFSET = 1L
        const val BATCH = 20
        const val DEFAULT_PAGE = 200
        const val MAX_PAGE = 5000
    }
}
