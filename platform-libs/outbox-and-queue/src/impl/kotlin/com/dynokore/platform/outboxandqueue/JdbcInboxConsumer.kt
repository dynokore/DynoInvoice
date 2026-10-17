package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
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
    constructor(
        private val subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        private val reader: EventLogReader,
        private val scope: TenantScope,
        private val dsl: DSLContext,
        private val handler: EventHandler,
        private val rebuildRequired: RebuildRequiredSink,
        private val clock: Clock = Clock.systemUTC(),
        private val randomUnit: () -> Double = { ThreadLocalRandom.current().nextDouble() },
    ) : InboxConsumer {
        private val json = EnvelopeJson()
        private val consumer = subscription.consumer
        private val topic = subscription.topic
        private val s = SqlIdentifier.require(subscription.schema, "schema")
        private val offsets: Table<*> = DSL.table(DSL.name(s, OutboxSchema.CONSUMER_OFFSET))
        private val inbox: Table<*> = DSL.table(DSL.name(s, OutboxSchema.INBOX))
        private val sequences: Table<*> = DSL.table(DSL.name(s, OutboxSchema.CONSUMER_SEQUENCE))

        /** The offset row's own columns, qualified by the table name: in `ON CONFLICT DO UPDATE` a bare name is ambiguous. */
        private val storedNext = stored("next_offset", NEXT_OFFSET)
        private val storedAttempts = stored("attempts", ATTEMPTS)
        private var stopped = false
        private val recordTypes =
            catalogue.recordTypes().also {
                check(subscription.recordTypes == null || subscription.recordTypes == it) {
                    "consumer $consumer declares record types ${subscription.recordTypes}, catalogue has $it (INV-Q-3809)"
                }
            }

        @Synchronized
        override fun pollOnce(): PollResult {
            val (next, notBefore) = position()
            if (notBefore != null && clock.instant().isBefore(notBefore)) return PollResult(0, 0, 0, 0, 0)
            val records =
                try {
                    reader.read(topic, next - 1, BATCH)
                } catch (behind: RebuildRequired) {
                    if (!stopped) {
                        rebuildRequired.raise(
                            RebuildRequiredIncident(
                                consumer,
                                topic,
                                behind.nextOffset,
                                behind.watermark,
                                clock.instant(),
                            ),
                        )
                        stopped = true
                    }
                    return PollResult(0, 0, 0, 0, 0, rebuildRequired = true)
                }
            stopped = false
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
        ) = deadLetter(LogRecord(REPLAY_OFFSET, raw), reason)

        /** Applies a valid replayed [envelope] in its tenant's transaction (the caller's when one is open). */
        internal fun applyChecked(envelope: Envelope): ReplayOutcome {
            val step =
                scope.bindWorkload(envelope.tenant) {
                    scope.transactions.inTransaction { apply(envelope) }
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
            val envelope =
                try {
                    json.read(record.envelopeJson)
                } catch (violation: ContractViolation) {
                    deadLetter(record, violation.message ?: "contract violation")
                    return Step.DEAD_LETTER
                }
            val state = beginAttempt(record.offset)
            if (state != null && state.attempts > subscription.retry.maxAttempts) {
                // Earlier attempts were recorded but never reported a failure (the process died in the handler).
                deadLetter(record, "$EXHAUSTED after ${state.attempts - 1} attempts: the handler did not complete")
                return Step.DEAD_LETTER
            }
            return try {
                scope.bindWorkload(envelope.tenant) {
                    scope.transactions.inTransaction { applyInTransaction(record, envelope) }
                }
            } catch (violation: ContractViolation) {
                deadLetter(record, violation.message ?: "contract violation")
                Step.DEAD_LETTER
            } catch (failure: Throwable) {
                // The process is not fit to carry on after an OutOfMemoryError or another VirtualMachineError: the
                // recorded attempt stands, no backoff. A StackOverflowError is the record's (deep payload): it backs off.
                if (failure is VirtualMachineError && failure !is StackOverflowError) throw failure
                if (state != null && failed(record, state, failure)) return Step.DEAD_LETTER
                throw failure
            }
        }

        /** Records the failure in the offset row; true when the record was dead-lettered instead of scheduled. */
        private fun failed(
            record: LogRecord,
            state: AttemptState,
            failure: Throwable,
        ): Boolean {
            val retry = subscription.retry
            val now = clock.instant()
            if (subscription.classifier.classify(failure) == FailureKind.RECORD) {
                if (state.attempts >= retry.maxAttempts) {
                    deadLetter(record, "$EXHAUSTED after ${state.attempts} attempts: ${failure.javaClass.name}")
                    return true
                }
                schedule(
                    record.offset,
                    0,
                    state.infraFailures,
                    state.firstInfra,
                    now.plus(retry.delayAfter(state.attempts, randomUnit())),
                )
                return false
            }
            val first = state.firstInfra ?: now
            if (!now.isBefore(first.plus(retry.infrastructureGiveUp))) {
                deadLetter(record, "$INFRA_EXHAUSTED ${retry.infrastructureGiveUp}: ${failure.javaClass.name}")
                return true
            }
            val infra = state.infraFailures + 1
            schedule(record.offset, 1, infra, first, now.plus(retry.delayAfter(infra, randomUnit())))
            return false
        }

        private class AttemptState(
            val attempts: Int,
            val infraFailures: Int,
            val firstInfra: Instant?,
        )

        /** In its own transaction, before the handler runs: counts this delivery on the offset row (INV-Q-3108). */
        private fun beginAttempt(offset: Long): AttemptState? =
            platform {
                dsl
                    .insertInto(offsets, CONSUMER, TOPIC, NEXT_OFFSET, ATTEMPTS)
                    .values(
                        DSL.`val`(consumer, SQLDataType.CLOB),
                        DSL.`val`(topic, SQLDataType.CLOB),
                        DSL.inline(FIRST_OFFSET),
                        DSL.inline(1),
                    ).onConflict(CONSUMER, TOPIC)
                    .doUpdate()
                    .set(ATTEMPTS, storedAttempts.plus(1))
                    .where(storedNext.le(offset))
                    .returning(ATTEMPTS, INFRA_FAILURES, FIRST_INFRA_FAILURE_AT)
                    .fetchOne()
                    ?.let {
                        AttemptState(
                            it.get(ATTEMPTS),
                            it.get(INFRA_FAILURES),
                            it.get(FIRST_INFRA_FAILURE_AT)?.toInstant(),
                        )
                    }
            }

        /** Gives back [uncount] attempts (an infrastructure failure does not use the bound) and sets the next wait. */
        private fun schedule(
            offset: Long,
            uncount: Int,
            infraFailures: Int,
            firstInfra: Instant?,
            notBefore: Instant,
        ) {
            platform {
                dsl
                    .update(offsets)
                    .set(ATTEMPTS, DSL.greatest(ATTEMPTS.minus(uncount), DSL.inline(0)))
                    .set(INFRA_FAILURES, infraFailures)
                    .set(FIRST_INFRA_FAILURE_AT, firstInfra?.let(::utc))
                    .set(RETRY_NOT_BEFORE, utc(notBefore))
                    .where(CONSUMER.eq(consumer).and(TOPIC.eq(topic)).and(NEXT_OFFSET.le(offset)))
                    .execute()
            }
        }

        private fun applyInTransaction(
            record: LogRecord,
            envelope: Envelope,
        ): Step {
            if (record.offset < lockOffset()) return Step.DUPLICATE
            val step = apply(envelope)
            advance(record.offset)
            return step
        }

        private fun apply(envelope: Envelope): Step {
            if (!claimInbox(envelope)) return Step.DUPLICATE
            if (subscription.trackSources &&
                envelope.type != ReplayProtocol.REPLAY_COMPLETED
            ) {
                recordSource(envelope)
            }
            // A record-topic event is deduped by event id only: no docsequence check, no held row (INV-Q-3809).
            val sequence = envelope.docSequence?.takeUnless { envelope.type in recordTypes }
            if (sequence != null && sequence <= heldSequence(envelope)) return Step.STALE
            val outcome = handler.handle(envelope)
            if (sequence != null) recordSequence(envelope, sequence)
            return when (outcome) {
                HandleOutcome.APPLIED -> Step.APPLIED
                HandleOutcome.IGNORED -> Step.IGNORED
            }
        }

        private fun position(): Pair<Long, Instant?> =
            platform {
                dsl
                    .select(NEXT_OFFSET, RETRY_NOT_BEFORE)
                    .from(offsets)
                    .where(CONSUMER.eq(consumer).and(TOPIC.eq(topic)))
                    .fetchOne()
                    ?.let { it.get(NEXT_OFFSET) to it.get(RETRY_NOT_BEFORE)?.toInstant() } ?: (FIRST_OFFSET to null)
            }

        // The offset and source-seen tables are `PLATFORM_SCOPED`, which tenancy-jooq writes only under the platform
        // binding, while the offset moves in the record's tenant transaction (INV-AC-2205): these three calls go through
        // sys-owned definer routines (INV-Q-5873).
        private fun lockOffset(): Long =
            dsl.select(JooqRoutines.consumerOffsetLock(s, consumer, topic)).fetchSingle().value1()

        private fun advance(offset: Long) {
            dsl.select(JooqRoutines.consumerOffsetAdvance(s, consumer, topic, offset + 1)).fetch()
        }

        private fun claimInbox(envelope: Envelope): Boolean =
            dsl
                .insertInto(inbox, CONSUMER, EVENT_ID, TENANT_ID)
                .values(
                    DSL.`val`(consumer, SQLDataType.CLOB),
                    DSL.`val`(envelope.id.value, SQLDataType.CLOB),
                    DSL.`val`(envelope.tenant.value, SQLDataType.UUID),
                ).onConflictDoNothing()
                .execute() == 1

        /**
         * INV-Q-3669: the source of an event on an 'every service' topic joins the consumer's seen set (a completion marker
         * from a service never seen changes nothing, so it does not join).
         */
        private fun recordSource(envelope: Envelope) {
            dsl
                .select(
                    JooqRoutines.consumerSourceSeenRecord(s, consumer, CatalogueEntry.EVERY_SERVICE, envelope.source),
                ).fetch()
        }

        private fun heldSequence(envelope: Envelope): Long =
            dsl
                .select(LAST_VALUE)
                .from(sequences)
                .where(
                    CONSUMER
                        .eq(consumer)
                        .and(TENANT_ID.eq(envelope.tenant.value))
                        .and(SOURCE.eq(envelope.source))
                        .and(SUBJECT.eq(envelope.subject)),
                ).fetchOne()
                ?.get(LAST_VALUE) ?: 0L

        private fun recordSequence(
            envelope: Envelope,
            sequence: Long,
        ) {
            dsl
                .insertInto(sequences, CONSUMER, SOURCE, SUBJECT, TENANT_ID, LAST_VALUE)
                .values(
                    DSL.`val`(consumer, SQLDataType.CLOB),
                    DSL.`val`(envelope.source, SQLDataType.CLOB),
                    DSL.`val`(envelope.subject, SQLDataType.CLOB),
                    DSL.`val`(envelope.tenant.value, SQLDataType.UUID),
                    DSL.`val`(sequence, SQLDataType.BIGINT),
                ).onConflict(CONSUMER, TENANT_ID, SOURCE, SUBJECT)
                .doUpdate()
                .set(LAST_VALUE, DSL.excluded(LAST_VALUE))
                .execute()
        }

        /**
         * Records the dead letter and moves the offset past it in one platform transaction, the letter through the
         * `<svc>_sys`-owned [OutboxSchema.DEAD_LETTER_RECORD] (a letter may have no tenant, so no tenant policy admits it).
         * The offset upsert resets attempts, infra_failures, first_infra_failure_at and retry_not_before when the new
         * offset is greater than the stored one and keeps the stored value otherwise.
         */
        private fun deadLetter(
            record: LogRecord,
            reason: String,
        ) {
            platform {
                val letter =
                    DSL.function(
                        DSL.name(s, OutboxSchema.DEAD_LETTER_RECORD),
                        SQLDataType.OTHER,
                        DSL.`val`(consumer, SQLDataType.CLOB),
                        DSL.`val`(topic, SQLDataType.CLOB),
                        DSL.`val`(record.offset, SQLDataType.BIGINT),
                        DSL.`val`(json.tenantOf(record.envelopeJson)?.value, SQLDataType.UUID),
                        DSL.`val`(reason, SQLDataType.CLOB),
                        DSL.`val`(record.envelopeJson, SQLDataType.CLOB),
                    )
                dsl.select(letter).fetch()
                val moved = DSL.excluded(NEXT_OFFSET).gt(storedNext)
                dsl
                    .insertInto(offsets, CONSUMER, TOPIC, NEXT_OFFSET)
                    .values(
                        DSL.`val`(consumer, SQLDataType.CLOB),
                        DSL.`val`(topic, SQLDataType.CLOB),
                        DSL.`val`(record.offset + 1, SQLDataType.BIGINT),
                    ).onConflict(CONSUMER, TOPIC)
                    .doUpdate()
                    .set(NEXT_OFFSET, DSL.greatest(storedNext, DSL.excluded(NEXT_OFFSET)))
                    .set(ATTEMPTS, DSL.`when`(moved, DSL.inline(0)).otherwise(storedAttempts))
                    .set(
                        INFRA_FAILURES,
                        DSL.`when`(moved, DSL.inline(0)).otherwise(stored("infra_failures", INFRA_FAILURES)),
                    ).set(
                        FIRST_INFRA_FAILURE_AT,
                        DSL
                            .`when`(
                                moved,
                                DSL.castNull(FIRST_INFRA_FAILURE_AT),
                            ).otherwise(stored("first_infra_failure_at", FIRST_INFRA_FAILURE_AT)),
                    ).set(
                        RETRY_NOT_BEFORE,
                        DSL
                            .`when`(
                                moved,
                                DSL.castNull(RETRY_NOT_BEFORE),
                            ).otherwise(stored("retry_not_before", RETRY_NOT_BEFORE)),
                    ).execute()
            }
        }

        /**
         * [block] in its own transaction under the platform binding: the offset and the dead letters are no tenant's.
         * Inline, so the frames of the lane-gate call belong to this class and not to the routines file.
         */
        private inline fun <T> platform(crossinline block: () -> T): T = scope.platformTransaction { block() }

        private fun <T> stored(
            column: String,
            like: Field<T>,
        ): Field<T> = DSL.field(DSL.name(s, OutboxSchema.CONSUMER_OFFSET, column), like.dataType)

        private fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

        private enum class Step { APPLIED, IGNORED, DUPLICATE, STALE, DEAD_LETTER }

        private companion object {
            const val BATCH = 100
            const val FIRST_OFFSET = 1L
            const val EXHAUSTED = "handler failed, retries exhausted"
            const val INFRA_EXHAUSTED = "handler failed on infrastructure failures for"
            const val REPLAY_OFFSET = 0L // a replayed record has no position in the topic
            val CONSUMER = DSL.field(DSL.name("consumer"), SQLDataType.CLOB)
            val TOPIC = DSL.field(DSL.name("topic"), SQLDataType.CLOB)
            val NEXT_OFFSET = DSL.field(DSL.name("next_offset"), SQLDataType.BIGINT)
            val ATTEMPTS = DSL.field(DSL.name("attempts"), SQLDataType.INTEGER)
            val INFRA_FAILURES = DSL.field(DSL.name("infra_failures"), SQLDataType.INTEGER)
            val FIRST_INFRA_FAILURE_AT =
                DSL.field(
                    DSL.name("first_infra_failure_at"),
                    SQLDataType.TIMESTAMPWITHTIMEZONE,
                )
            val RETRY_NOT_BEFORE = DSL.field(DSL.name("retry_not_before"), SQLDataType.TIMESTAMPWITHTIMEZONE)
            val EVENT_ID = DSL.field(DSL.name("event_id"), SQLDataType.CLOB)
            val TENANT_ID = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
            val SOURCE = DSL.field(DSL.name("source"), SQLDataType.CLOB)
            val SUBJECT = DSL.field(DSL.name("subject"), SQLDataType.CLOB)
            val LAST_VALUE = DSL.field(DSL.name("last_value"), SQLDataType.BIGINT)
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
