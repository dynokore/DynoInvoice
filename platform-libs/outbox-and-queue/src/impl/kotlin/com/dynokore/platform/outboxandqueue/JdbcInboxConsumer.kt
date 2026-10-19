package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom

/**
 * [InboxConsumer] over JDBC, one instance per (consumer, topic). Each record is applied in
 * one tenant transaction in the consumer's own schema (INV-REQ-1222, ARC 4.2 / TPL-06):
 *
 * 1. the envelope is validated; a violation goes to `event_dead_letter` and the consumer moves
 *    on (no tenant is known yet, so this uses the claim connection, never tenant data);
 * 2. the record's `tenantid` is bound (ARC-21) and the offset row is locked, so two instances of
 *    one consumer never apply a record twice;
 * 3. the event id is inserted into the inbox; an id already there means the event was applied:
 *    acknowledged and discarded without effect (INV-REQ-1164, INV-AC-2149);
 * 4. a `docsequence` not newer than the one held for (source, subject) is ignored; sequences are
 *    never compared across producers (INV-REQ-1221);
 * 5. the handler runs in this transaction (its state change and outbox rows join it);
 * 6. a handler failure rolls the record back; it is retried after a bounded, jittered backoff and dead-lettered
 *    when [ConsumerRetryPolicy.maxAttempts] is spent (a [ContractViolation] at once), see [pollOnce];
 * 7. the new sequence and the offset are written, and everything commits together. A kill before
 *    the commit leaves no inbox row, so the restart applies the event exactly once (INV-AC-2205).
 *
 * [catalogue] gives the record-topic event types (INV-Q-3809); a [ConsumerSubscription.recordTypes] declaration that
 * differs from them fails construction, so a service cannot run with record events under the sequence check.
 *
 * Outside any tenant transaction the consumer works in a transaction of its own under the platform binding: it only
 * reads and writes the offset (a `PLATFORM_SCOPED` table, no tenant data) to know where to poll, and records dead
 * letters through the `<svc>_sys`-owned [OutboxSchema.DEAD_LETTER_RECORD]; the inbox and sequence tables (row-level
 * security) are reached only inside a tenant transaction.
 *
 * When the reader reports that this consumer's next position is at or below the topic's purge watermark
 * ([RebuildRequired], INV-REQ-1205 b6), the poll applies nothing and moves nothing: the consumer stops this topic
 * (its instances for other topics carry on) and raises a 'rebuild required' incident through [rebuildRequired],
 * once until it resumes. It resumes by itself once the recovery path has moved its offset past the watermark.
 */
class JdbcInboxConsumer
    @JvmOverloads
    @Suppress("LongParameterList") // constructor injection under CS-05; INV-Q-4629 (CS-50)
    constructor(
        private val subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        private val reader: EventLogReader,
        private val scope: TenantScope,
        dsl: DSLContext,
        handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        private val clock: Clock = Clock.systemUTC(),
        randomUnit: () -> Double = { ThreadLocalRandom.current().nextDouble() },
    ) : InboxConsumer {
        private val json = EnvelopeJson()
        private val consumer = subscription.consumer
        private val topic = subscription.topic
        private val s = SqlIdentifier.require(subscription.schema, "schema")
        private val offsets = InboxOffsets(dsl, scope, s, consumer, topic, json)
        private val retry = InboxRetry(subscription, clock, randomUnit, offsets)
        private val rebuild = RebuildStop(consumer, topic, rebuildRequired, clock)
        private val ledger =
            InboxLedger(
                dsl,
                s,
                consumer,
                subscription.trackSources,
                catalogue.recordTypes().also {
                    check(subscription.recordTypes == null || subscription.recordTypes == it) {
                        "consumer $consumer declares record types ${subscription.recordTypes}, " +
                            "catalogue has $it (INV-Q-3809)"
                    }
                },
                handler,
            )

        @Synchronized
        override fun pollOnce(): PollResult {
            val (next, notBefore) = offsets.position()
            val waiting = notBefore != null && clock.instant().isBefore(notBefore)
            return if (waiting) PollResult(0, 0, 0, 0, 0) else readAndApply(next)
        }

        private fun readAndApply(next: Long): PollResult {
            val records =
                try {
                    reader.read(topic, next - 1, BATCH)
                } catch (behind: RebuildRequired) {
                    rebuild.raise(behind)
                    return PollResult(0, 0, 0, 0, 0, rebuildRequired = true)
                }
            rebuild.resume()
            val counts = IntArray(Step.entries.size)
            for (record in records) {
                counts[processRecord(record).ordinal]++
            }
            return PollResult(
                applied = counts[Step.APPLIED.ordinal],
                ignored = counts[Step.IGNORED.ordinal],
                duplicates = counts[Step.DUPLICATE.ordinal],
                stale = counts[Step.STALE.ordinal],
                deadLettered = counts[Step.DEAD_LETTER.ordinal],
            )
        }

        /**
         * Applies a replayed dead letter ([raw] is its stored envelope text) through the same inbox, sequence and
         * handler rules as a polled record, in the caller's tenant transaction when one is open ([ReplayDelivery]),
         * without touching the offset. An envelope that is still invalid, or belongs to another tenant than
         * [expectedTenant], is dead-lettered again.
         */
        fun applyReplayed(
            raw: String,
            expectedTenant: TenantId,
        ): ReplayOutcome =
            when (val check = checkReplayed(raw, expectedTenant)) {
                is ReplayCheck.Invalid -> {
                    deadLetterReplayed(raw, check.reason)
                    ReplayOutcome.REJECTED
                }

                is ReplayCheck.Valid -> {
                    applyChecked(check.envelope)
                }
            }

        /**
         * The checks of [applyReplayed] that come before its transaction: [raw] parses as an envelope and belongs to
         * [expectedTenant]. Opens no connection ([JdbcReplayDelivery] decides with it outside any transaction).
         */
        internal fun checkReplayed(
            raw: String,
            expectedTenant: TenantId,
        ): ReplayCheck =
            try {
                val envelope = json.read(raw)
                if (envelope.tenant != expectedTenant) {
                    throw ContractViolation("replayed envelope is for another tenant")
                }
                ReplayCheck.Valid(envelope)
            } catch (violation: ContractViolation) {
                ReplayCheck.Invalid(violation.message ?: "contract violation")
            }

        /** Records the dead letter of a replayed envelope [checkReplayed] rejected, on the claim connection. */
        internal fun deadLetterReplayed(
            raw: String,
            reason: String,
        ) = offsets.deadLetter(LogRecord(REPLAY_OFFSET, raw), reason)

        /** Applies a valid replayed [envelope] in its tenant's transaction (the caller's when one is open). */
        internal fun applyChecked(envelope: Envelope): ReplayOutcome {
            val step =
                scope.bindWorkload(envelope.tenant) {
                    scope.transactions.inTransaction { ledger.apply(envelope) }
                }
            return when (step) {
                Step.APPLIED -> ReplayOutcome.APPLIED
                Step.IGNORED -> ReplayOutcome.IGNORED
                Step.DUPLICATE -> ReplayOutcome.DUPLICATE
                Step.STALE -> ReplayOutcome.STALE
                Step.DEAD_LETTER -> ReplayOutcome.REJECTED
            }
        }

        private fun processRecord(record: LogRecord): Step {
            val envelope = readOrDeadLetter(record) ?: return Step.DEAD_LETTER
            val state = offsets.beginAttempt(record.offset)
            return if (state != null && state.attempts > subscription.retry.maxAttempts) {
                // Earlier attempts were recorded but never reported a failure (the process died in the handler).
                offsets.deadLetter(
                    record,
                    "${InboxRetry.EXHAUSTED} after ${state.attempts - 1} attempts: the handler did not complete",
                )
                Step.DEAD_LETTER
            } else {
                deliver(record, envelope, state)
            }
        }

        /** The record's envelope, or null after the record was dead-lettered as a contract violation. */
        private fun readOrDeadLetter(record: LogRecord): Envelope? =
            try {
                json.read(record.envelopeJson)
            } catch (violation: ContractViolation) {
                offsets.deadLetter(record, violation.message ?: "contract violation")
                null
            }

        private fun deliver(
            record: LogRecord,
            envelope: Envelope,
            state: AttemptState?,
        ): Step =
            try {
                scope.bindWorkload(envelope.tenant) {
                    scope.transactions.inTransaction {
                        if (record.offset < offsets.lockOffset()) {
                            Step.DUPLICATE
                        } else {
                            ledger.apply(envelope).also { offsets.advance(record.offset) }
                        }
                    }
                }
            } catch (violation: ContractViolation) {
                offsets.deadLetter(record, violation.message ?: "contract violation")
                Step.DEAD_LETTER
            } catch (expected: StackOverflowError) {
                // A StackOverflowError is the record's (deep payload): it backs off.
                retry.settle(record, state, expected)
                Step.DEAD_LETTER
            } catch (expected: VirtualMachineError) {
                // The process is not fit to carry on after an OutOfMemoryError or another VirtualMachineError: the
                // recorded attempt stands, no backoff.
                throw expected
            } catch (expected: Throwable) {
                retry.settle(record, state, expected)
                Step.DEAD_LETTER
            }

        private companion object {
            const val BATCH = 100
            const val REPLAY_OFFSET = 0L // a replayed record has no position in the topic
        }
    }

/** The pre-transaction verdict on a replayed envelope ([JdbcInboxConsumer.checkReplayed]). */
internal sealed interface ReplayCheck {
    class Valid(
        val envelope: Envelope,
    ) : ReplayCheck

    class Invalid(
        val reason: String,
    ) : ReplayCheck
}
