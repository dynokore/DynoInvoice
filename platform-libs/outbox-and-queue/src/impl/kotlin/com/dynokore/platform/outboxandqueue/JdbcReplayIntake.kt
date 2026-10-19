package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier
import org.jooq.DSLContext
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

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
    @Suppress("LongParameterList") // constructor injection under CS-05; INV-Q-4629 (CS-50)
    constructor(
        subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        private val producer: String,
        reader: EventLogReader,
        private val scope: TenantScope,
        private val dsl: DSLContext,
        handler: EventHandler,
        private val sink: RebuildRequiredSink,
        private val clock: Clock = Clock.systemUTC(),
    ) : InboxConsumer {
        private val consumer = subscription.consumer
        private val s = SqlIdentifier.require(subscription.schema, "schema")
        private val replays: Table<*> = DSL.table(DSL.name(s, OutboxSchema.CONSUMER_REPLAY))
        private val markers: Table<*> = DSL.table(DSL.name(s, OutboxSchema.CONSUMER_REPLAY_MARKER))
        private val seen: Table<*> = DSL.table(DSL.name(s, OutboxSchema.CONSUMER_SOURCE_SEEN))
        private val json = ReplayJson()
        private val inbox =
            JdbcInboxConsumer(
                subscription.copy(trackSources = producer == CatalogueEntry.EVERY_SERVICE),
                catalogue,
                reader,
                scope,
                dsl,
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
            val done = completedReplays()
            for ((replayId, topics) in done) {
                val now = clock.instant()
                topics.forEach { sink.close(RebuildRequiredClosed(consumer, it, replayId, now)) }
                scope.platformTransaction {
                    dsl
                        .update(replays)
                        .set(CLOSED_AT, now.atOffset(ZoneOffset.UTC))
                        .where(REPLAY_ID.eq(replayId).and(CONSUMER.eq(consumer)))
                        .execute()
                }
            }
            return done.size
        }

        /** The completed replays whose incidents are not yet closed: id and topics. */
        private fun completedReplays(): List<Pair<UUID, List<String>>> =
            scope.platformTransaction {
                val r = replays.`as`("r")
                val o = replays.`as`("o")
                dsl
                    .select(replayId("r"), TOPICS)
                    .from(r)
                    .where(
                        // A replay addressed to several producers closes once every addressee has completed (INV-Q-3788
                        // A).
                        CONSUMER
                            .eq(consumer)
                            .and(PRODUCER.eq(producer))
                            .and(COMPLETED_AT.isNotNull)
                            .and(CLOSED_AT.isNull)
                            .and(
                                DSL.notExists(
                                    DSL
                                        .selectOne()
                                        .from(o)
                                        .where(
                                            replayId("o")
                                                .eq(replayId("r"))
                                                .and(
                                                    DSL
                                                        .field(
                                                            DSL.name("o", "consumer"),
                                                            SQLDataType.CLOB,
                                                        ).eq(DSL.field(DSL.name("r", "consumer"), SQLDataType.CLOB)),
                                                ).and(
                                                    DSL
                                                        .field(
                                                            DSL.name("o", "completed_at"),
                                                            SQLDataType.TIMESTAMPWITHTIMEZONE,
                                                        ).isNull,
                                                ),
                                        ),
                                ),
                            ),
                    ).fetch()
                    .map { it.get(replayId("r")) to it.get(TOPICS).toList() }
            }

        private inner class MarkerAwareHandler(
            private val delegate: EventHandler,
        ) : EventHandler {
            override fun handle(event: Envelope): HandleOutcome =
                if (event.type ==
                    ReplayProtocol.REPLAY_COMPLETED
                ) {
                    recordMarker(event)
                } else {
                    delegate.handle(event)
                }

            private fun recordMarker(event: Envelope): HandleOutcome {
                val replayId = json.readCompleted(event.dataJson)
                // The marker is recorded in a platform-bound transaction of its own, whatever tenant the event is
                // published under (owner ruling on INV-T-5306, keeping the earlier behaviour).
                val pending =
                    scope.platformTransaction {
                        if (!lockPending(replayId)) {
                            false
                        } else {
                            dsl
                                .insertInto(markers, REPLAY_ID, SOURCE)
                                .values(
                                    DSL.`val`(replayId, SQLDataType.UUID),
                                    DSL.`val`(event.source, SQLDataType.CLOB),
                                ).onConflictDoNothing()
                                .execute()
                            if (complete(replayId)) {
                                dsl
                                    .update(replays)
                                    .set(COMPLETED_AT, clock.instant().atOffset(ZoneOffset.UTC))
                                    .where(REPLAY_ID.eq(replayId).and(CONSUMER.eq(consumer)).and(PRODUCER.eq(producer)))
                                    .execute()
                            }
                            true
                        }
                    }
                return if (pending) HandleOutcome.APPLIED else HandleOutcome.IGNORED
            }

            /** Locks the pending replay [replayId] of this pair; false when there is none. */
            private fun lockPending(replayId: UUID): Boolean =
                dsl
                    .selectOne()
                    .from(replays)
                    .where(
                        REPLAY_ID
                            .eq(
                                replayId,
                            ).and(CONSUMER.eq(consumer))
                            .and(PRODUCER.eq(producer))
                            .and(COMPLETED_AT.isNull),
                    ).forUpdate()
                    .fetchOne() != null

            /**
             * A named pair completes on its producer's marker; an 'every service' pair once every source seen has
             * answered (INV-Q-3669).
             */
            private fun complete(replayId: UUID): Boolean {
                val markers = markerSources(replayId)
                if (producer != CatalogueEntry.EVERY_SERVICE) return "/gst/$producer" in markers
                return seenSources().all { it in markers }
            }

            private fun markerSources(replayId: UUID): Set<String> =
                dsl
                    .select(SOURCE)
                    .from(markers)
                    .where(REPLAY_ID.eq(replayId))
                    .fetch(SOURCE)
                    .toSet()

            private fun seenSources(): Set<String> =
                dsl
                    .select(SOURCE)
                    .from(seen)
                    .where(CONSUMER.eq(consumer).and(PRODUCER.eq(CatalogueEntry.EVERY_SERVICE)))
                    .fetch(SOURCE)
                    .toSet()
        }

        private companion object {
            val REPLAY_ID = DSL.field(DSL.name("replay_id"), SQLDataType.UUID)
            val CONSUMER = DSL.field(DSL.name("consumer"), SQLDataType.CLOB)
            val PRODUCER = DSL.field(DSL.name("producer"), SQLDataType.CLOB)
            val SOURCE = DSL.field(DSL.name("source"), SQLDataType.CLOB)
            val TOPICS = DSL.field(DSL.name("topics"), SQLDataType.CLOB.array())
            val COMPLETED_AT = DSL.field(DSL.name("completed_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
            val CLOSED_AT = DSL.field(DSL.name("closed_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)

            fun replayId(alias: String) = DSL.field(DSL.name(alias, "replay_id"), SQLDataType.UUID)
        }
    }
