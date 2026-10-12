package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier
import java.sql.Connection
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

/**
 * The consumer's intake of one replay topic, `gst.<producer>.replay-<consumer>.v1` or, for an 'every service' pair,
 * `gst.platform.replay-<consumer>.v1` (INV-REQ-1205 b4; rulings INV-Q-3601, INV-Q-3625). It is an ordinary consumer of
 * that topic, which only this consumer's roles may read: each replayed event is applied through the consumer's own
 * inbox, `docsequence` rule, handler and transaction ([JdbcInboxConsumer]), under the consumer name its live
 * subscriptions to that producer use, so a state it already holds, or a newer one, is STALE.
 *
 * The `ReplayCompleted` marker never reaches [handler]: in the marker's transaction the intake records it and, when it
 * completes the pending replay it answers (from the pair's producer; on an 'every service' topic, once a marker for
 * the replay has been applied from each source the consumer has seen there and on that producer's live topics, owner
 * ruling INV-Q-3669), marks the pair's replay complete. A replay addressed to several producers under one replay id
 * (owner ruling INV-Q-3788 A) completes per pair, on each producer's own intake, and its incidents close only once
 * every addressee has completed. A marker for no pending replay is applied and changes nothing. After
 * each poll the intake closes the 'rebuild required' incident of each topic of every completed replay through
 * [sink] (INV-Q-3644, INV-AC-3658), and records that it did so.
 *
 * @param subscription the replay topic's subscription; [ConsumerSubscription.consumer] is the consumer name of the
 *   live subscriptions to [producer].
 * @param catalogue the topics catalogue; record-topic types are taken from it (INV-Q-3809).
 * @param producer the pair's catalogue producer value (a service name or 'every service').
 */
class JdbcReplayIntake
@JvmOverloads
constructor(
    subscription: ConsumerSubscription,
    catalogue: EventCatalogue,
    private val producer: String,
    reader: EventLogReader,
    private val claimSource: DataSource,
    private val scope: TenantScope,
    handler: EventHandler,
    private val sink: RebuildRequiredSink,
    private val clock: Clock = Clock.systemUTC(),
) : InboxConsumer {
    private val consumer = subscription.consumer
    private val s = SqlIdentifier.require(subscription.schema, "schema")
    private val replays = "$s.${OutboxSchema.CONSUMER_REPLAY}"
    private val json = ReplayJson()
    private val inbox =
        JdbcInboxConsumer(
            subscription.copy(trackSources = producer == CatalogueEntry.EVERY_SERVICE),
            catalogue,
            reader,
            claimSource,
            scope,
            MarkerAwareHandler(handler),
            sink,
            clock,
        )

    override fun pollOnce(): PollResult {
        val result = inbox.pollOnce()
        closeCompleted()
        return result
    }

    /** Closes the incidents of completed replays not yet closed (at least once). Returns the number of replays. */
    fun closeCompleted(): Int {
        val done =
            claimSource.connection.use { connection ->
                connection.prepareStatement(
                    // A replay addressed to several producers closes once every addressee has completed (INV-Q-3788 A).
                    "select replay_id, topics from $replays r where consumer = ? and producer = ? " +
                        "and completed_at is not null and closed_at is null and not exists (select 1 from $replays o " +
                        "where o.replay_id = r.replay_id and o.consumer = r.consumer and o.completed_at is null)",
                ).use {
                    it.setString(1, consumer)
                    it.setString(2, producer)
                    it.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                @Suppress("UNCHECKED_CAST")
                                add(rows.getObject(1, UUID::class.java) to (rows.getArray(2).array as Array<String>).toList())
                            }
                        }
                    }
                }
            }
        for ((replayId, topics) in done) {
            val now = clock.instant()
            topics.forEach { sink.close(RebuildRequiredClosed(consumer, it, replayId, now)) }
            claimSource.connection.use { connection ->
                connection.prepareStatement("update $replays set closed_at = ? where replay_id = ? and consumer = ?").use {
                    it.setTimestamp(1, Timestamp.from(now))
                    it.setObject(2, replayId)
                    it.setString(3, consumer)
                    it.executeUpdate()
                }
            }
        }
        return done.size
    }

    private inner class MarkerAwareHandler(
        private val delegate: EventHandler,
    ) : EventHandler {
        override fun handle(event: Envelope): HandleOutcome {
            if (event.type != ReplayProtocol.REPLAY_COMPLETED) return delegate.handle(event)
            val replayId = json.readCompleted(event.dataJson)
            val connection = scope.connections.require()
            if (!lockPending(connection, replayId)) return HandleOutcome.IGNORED
            connection.prepareStatement(
                "insert into $s.${OutboxSchema.CONSUMER_REPLAY_MARKER} (replay_id, source) values (?, ?) on conflict do nothing",
            ).use {
                it.setObject(1, replayId)
                it.setString(2, event.source)
                it.executeUpdate()
            }
            if (complete(connection, replayId)) {
                val sql = "update $replays set completed_at = ? where replay_id = ? and consumer = ? and producer = ?"
                connection.prepareStatement(sql).use {
                    it.setTimestamp(1, Timestamp.from(clock.instant()))
                    it.setObject(2, replayId)
                    it.setString(3, consumer)
                    it.setString(4, producer)
                    it.executeUpdate()
                }
            }
            return HandleOutcome.APPLIED
        }

        /** Locks the pending replay [replayId] of this pair; false when there is none. */
        private fun lockPending(
            connection: Connection,
            replayId: UUID,
        ): Boolean = connection.prepareStatement(
            "select 1 from $replays where replay_id = ? and consumer = ? and producer = ? and completed_at is null for update",
        ).use {
            it.setObject(1, replayId)
            it.setString(2, consumer)
            it.setString(3, producer)
            it.executeQuery().use { rows -> rows.next() }
        }

        /**
         * A named pair completes on its producer's marker; an 'every service' pair once every source seen has
         * answered (INV-Q-3669).
         */
        private fun complete(
            connection: Connection,
            replayId: UUID,
        ): Boolean {
            val markers = markerSources(connection, replayId)
            if (producer != CatalogueEntry.EVERY_SERVICE) return "/gst/$producer" in markers
            return seenSources(connection).all { it in markers }
        }

        private fun markerSources(
            connection: Connection,
            replayId: UUID,
        ): Set<String> = connection.prepareStatement(
            "select source from $s.${OutboxSchema.CONSUMER_REPLAY_MARKER} where replay_id = ?",
        ).use {
            it.setObject(1, replayId)
            it.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }

        private fun seenSources(connection: Connection): Set<String> = connection.prepareStatement(
            "select source from $s.${OutboxSchema.CONSUMER_SOURCE_SEEN} where consumer = ? and producer = ?",
        ).use {
            it.setString(1, consumer)
            it.setString(2, CatalogueEntry.EVERY_SERVICE)
            it.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
    }
}
