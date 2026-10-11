package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom
import javax.sql.DataSource

/**
 * [InboxConsumer] over JDBC, one instance per (consumer, topic). Each record is applied in
 * one tenant transaction in the consumer's own schema (INV-REQ-1222, ARC 4.2 / TPL-06):
 *
 * 1. the envelope is validated; a violation goes to `event_dead_letter` and the consumer moves
 *    on (no tenant is known yet, so this uses the worker connection, never tenant data);
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
 * [claimSource] is the worker-role connection of ARC 4.3 step 1a: it only reads the offset to know
 * where to poll, and records dead letters; the inbox and sequence tables (row-level security) are
 * reached only inside a tenant transaction.
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
    private val reader: EventLogReader,
    private val claimSource: DataSource,
    private val scope: TenantScope,
    private val handler: EventHandler,
    private val rebuildRequired: RebuildRequiredSink,
    private val clock: Clock = Clock.systemUTC(),
    private val randomUnit: () -> Double = { ThreadLocalRandom.current().nextDouble() },
) : InboxConsumer {
    private val json = EnvelopeJson()
    private val consumer = subscription.consumer
    private val topic = subscription.topic
    private val s = SqlIdentifier.require(subscription.schema, "schema")
    private val offsets = "$s.${OutboxSchema.CONSUMER_OFFSET}"
    private var stopped = false

    @Synchronized
    override fun pollOnce(): PollResult {
        val (next, notBefore) = claimSource.connection.use { position(it) }
        if (notBefore != null && clock.instant().isBefore(notBefore)) return PollResult(0, 0, 0, 0, 0)
        val records =
            try {
                reader.read(topic, next - 1, BATCH)
            } catch (behind: RebuildRequired) {
                if (!stopped) {
                    rebuildRequired.raise(
                        RebuildRequiredIncident(consumer, topic, behind.nextOffset, behind.watermark, clock.instant()),
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
    ): ReplayOutcome {
        val envelope =
            try {
                json.read(raw).also {
                    if (it.tenant != expectedTenant) throw ContractViolation("replayed envelope is for another tenant")
                }
            } catch (violation: ContractViolation) {
                deadLetter(LogRecord(REPLAY_OFFSET, raw), violation.message ?: "contract violation")
                return ReplayOutcome.REJECTED
            }
        val step =
            scope.bind(envelope.tenant) {
                scope.transactions.inTransaction { apply(scope.connections.require(), envelope) }
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
            scope.bind(envelope.tenant) {
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
            schedule(record.offset, 0, state.infraFailures, state.firstInfra, now.plus(retry.delayAfter(state.attempts, randomUnit())))
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

    private class AttemptState(val attempts: Int, val infraFailures: Int, val firstInfra: Instant?)

    /** In its own transaction, before the handler runs: counts this delivery on the offset row (INV-Q-3108). */
    private fun beginAttempt(offset: Long): AttemptState? = claimSource.connection.use { connection ->
        connection.autoCommit = true
        connection.prepareStatement(
            "insert into $offsets (consumer, topic, next_offset, attempts) values (?, ?, $FIRST_OFFSET, 1) " +
                "on conflict (consumer, topic) do update set attempts = $offsets.attempts + 1 " +
                "where $offsets.next_offset <= ? returning attempts, infra_failures, first_infra_failure_at",
        ).use {
            it.setString(1, consumer)
            it.setString(2, topic)
            it.setLong(3, offset)
            it.executeQuery().use { rows ->
                if (!rows.next()) {
                    null
                } else {
                    AttemptState(rows.getInt(1), rows.getInt(2), rows.getTimestamp(3)?.toInstant())
                }
            }
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
        claimSource.connection.use { connection ->
            connection.autoCommit = true
            connection.prepareStatement(
                "update $offsets set attempts = greatest(attempts - ?, 0), infra_failures = ?, " +
                    "first_infra_failure_at = ?, retry_not_before = ? " +
                    "where consumer = ? and topic = ? and next_offset <= ?",
            ).use {
                it.setInt(1, uncount)
                it.setInt(2, infraFailures)
                it.setTimestamp(3, firstInfra?.let(java.sql.Timestamp::from))
                it.setTimestamp(4, java.sql.Timestamp.from(notBefore))
                it.setString(5, consumer)
                it.setString(6, topic)
                it.setLong(7, offset)
                it.executeUpdate()
            }
        }
    }

    private fun applyInTransaction(
        record: LogRecord,
        envelope: Envelope,
    ): Step {
        val connection = scope.connections.require()
        if (record.offset < lockOffset(connection)) return Step.DUPLICATE
        val step = apply(connection, envelope)
        advance(connection, record.offset)
        return step
    }

    private fun apply(
        connection: Connection,
        envelope: Envelope,
    ): Step {
        if (!claimInbox(connection, envelope)) return Step.DUPLICATE
        val sequence = envelope.docSequence
        if (sequence != null && sequence <= heldSequence(connection, envelope)) return Step.STALE
        val outcome = handler.handle(envelope)
        if (sequence != null) recordSequence(connection, envelope, sequence)
        return when (outcome) {
            HandleOutcome.APPLIED -> Step.APPLIED
            HandleOutcome.IGNORED -> Step.IGNORED
        }
    }

    private fun position(connection: Connection): Pair<Long, Instant?> = connection.prepareStatement(
        "select next_offset, retry_not_before from $offsets where consumer = ? and topic = ?",
    ).use {
        it.setString(1, consumer)
        it.setString(2, topic)
        it.executeQuery().use { rows ->
            if (rows.next()) rows.getLong(1) to rows.getTimestamp(2)?.toInstant() else FIRST_OFFSET to null
        }
    }

    private fun lockOffset(connection: Connection): Long {
        connection.prepareStatement(
            "insert into $offsets (consumer, topic, next_offset) values (?, ?, $FIRST_OFFSET) on conflict do nothing",
        ).use {
            it.setString(1, consumer)
            it.setString(2, topic)
            it.executeUpdate()
        }
        connection.prepareStatement("select next_offset from $offsets where consumer = ? and topic = ? for update").use {
            it.setString(1, consumer)
            it.setString(2, topic)
            it.executeQuery().use { rows ->
                check(rows.next()) { "offset row missing" }
                return rows.getLong(1)
            }
        }
    }

    private fun advance(
        connection: Connection,
        offset: Long,
    ) {
        connection.prepareStatement(
            "update $offsets set next_offset = ?, attempts = 0, infra_failures = 0, first_infra_failure_at = null, " +
                "retry_not_before = null where consumer = ? and topic = ?",
        ).use {
            it.setLong(1, offset + 1)
            it.setString(2, consumer)
            it.setString(3, topic)
            it.executeUpdate()
        }
    }

    private fun claimInbox(
        connection: Connection,
        envelope: Envelope,
    ): Boolean = connection.prepareStatement(
        "insert into $s.${OutboxSchema.INBOX} (consumer, event_id, tenant_id) values (?, ?, ?) on conflict do nothing",
    ).use {
        it.setString(1, consumer)
        it.setString(2, envelope.id.value)
        it.setObject(3, envelope.tenant.value)
        it.executeUpdate() == 1
    }

    private fun heldSequence(
        connection: Connection,
        envelope: Envelope,
    ): Long = connection.prepareStatement(
        "select last_value from $s.${OutboxSchema.CONSUMER_SEQUENCE} " +
            "where consumer = ? and tenant_id = ? and source = ? and subject = ?",
    ).use {
        it.setString(1, consumer)
        it.setObject(2, envelope.tenant.value)
        it.setString(3, envelope.source)
        it.setString(4, envelope.subject)
        it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
    }

    private fun recordSequence(
        connection: Connection,
        envelope: Envelope,
        sequence: Long,
    ) {
        connection.prepareStatement(
            """
            insert into $s.${OutboxSchema.CONSUMER_SEQUENCE} (consumer, source, subject, tenant_id, last_value)
            values (?, ?, ?, ?, ?)
            on conflict (consumer, tenant_id, source, subject) do update set last_value = excluded.last_value
            """.trimIndent(),
        ).use {
            it.setString(1, consumer)
            it.setString(2, envelope.source)
            it.setString(3, envelope.subject)
            it.setObject(4, envelope.tenant.value)
            it.setLong(5, sequence)
            it.executeUpdate()
        }
    }

    /** Records the dead letter and moves the offset past it in one worker-role transaction. */
    private fun deadLetter(
        record: LogRecord,
        reason: String,
    ) {
        claimSource.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                "insert into $s.${OutboxSchema.DEAD_LETTER} (consumer, topic, log_offset, tenant_id, reason, raw) " +
                    "values (?, ?, ?, ?, ?, ?)",
            ).use {
                it.setString(1, consumer)
                it.setString(2, topic)
                it.setLong(3, record.offset)
                it.setObject(4, json.tenantOf(record.envelopeJson)?.value, java.sql.Types.OTHER)
                it.setString(5, reason)
                it.setString(6, record.envelopeJson)
                it.executeUpdate()
            }
            connection.prepareStatement(
                "insert into $offsets (consumer, topic, next_offset) values (?, ?, ?) " +
                    "on conflict (consumer, topic) do update set next_offset = greatest($offsets.next_offset, excluded.next_offset), " +
                    "attempts = ${keepUnlessMoved("attempts", "0")}, infra_failures = ${keepUnlessMoved("infra_failures", "0")}, " +
                    "first_infra_failure_at = ${keepUnlessMoved("first_infra_failure_at", "null")}, " +
                    "retry_not_before = ${keepUnlessMoved("retry_not_before", "null")}",
            ).use {
                it.setString(1, consumer)
                it.setString(2, topic)
                it.setLong(3, record.offset + 1)
                it.executeUpdate()
            }
            connection.commit()
        }
    }

    /** SQL: [column]'s value, or [reset] when the offset moved past the row's record. */
    private fun keepUnlessMoved(
        column: String,
        reset: String,
    ) = "case when excluded.next_offset > $offsets.next_offset then $reset else $offsets.$column end"

    private enum class Step { APPLIED, IGNORED, DUPLICATE, STALE, DEAD_LETTER }

    private companion object {
        const val BATCH = 100
        const val FIRST_OFFSET = 1L
        const val EXHAUSTED = "handler failed, retries exhausted"
        const val INFRA_EXHAUSTED = "handler failed on infrastructure failures for"
        const val REPLAY_OFFSET = 0L // a replayed record has no position in the topic
    }
}
