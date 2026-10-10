package com.dynokore.platform.outboxandqueue

import java.sql.Connection
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
 * 6. the new sequence and the offset are written, and everything commits together. A kill before
 *    the commit leaves no inbox row, so the restart applies the event exactly once (INV-AC-2205).
 *
 * [claimSource] is the worker-role connection of ARC 4.3 step 1a: it only reads the offset to know
 * where to poll, and records dead letters; the inbox and sequence tables (row-level security) are
 * reached only inside a tenant transaction.
 */
class JdbcInboxConsumer(
    private val subscription: ConsumerSubscription,
    private val reader: EventLogReader,
    private val claimSource: DataSource,
    private val scope: TenantScope,
    private val handler: EventHandler,
) : InboxConsumer {
    private val json = EnvelopeJson()
    private val consumer = subscription.consumer
    private val topic = subscription.topic
    private val s = SqlIdentifier.require(subscription.schema, "schema")
    private val offsets = "$s.${OutboxSchema.CONSUMER_OFFSET}"

    override fun pollOnce(): PollResult {
        val next = claimSource.connection.use { nextOffset(it) }
        val counts = IntArray(Step.entries.size)
        for (record in reader.read(topic, next - 1, BATCH)) {
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

    private fun processRecord(record: LogRecord): Step {
        val envelope =
            try {
                json.read(record.envelopeJson)
            } catch (violation: ContractViolation) {
                deadLetter(record, violation)
                return Step.DEAD_LETTER
            }
        return scope.tenants.bind(envelope.tenant) {
            scope.transactions.inTransaction { applyInTransaction(record, envelope) }
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

    private fun nextOffset(connection: Connection): Long = connection.prepareStatement("select next_offset from $offsets where consumer = ? and topic = ?").use {
        it.setString(1, consumer)
        it.setString(2, topic)
        it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else FIRST_OFFSET }
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
        connection.prepareStatement("update $offsets set next_offset = ? where consumer = ? and topic = ?").use {
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
        "select last_value from $s.${OutboxSchema.CONSUMER_SEQUENCE} where consumer = ? and source = ? and subject = ?",
    ).use {
        it.setString(1, consumer)
        it.setString(2, envelope.source)
        it.setString(3, envelope.subject)
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
            on conflict (consumer, source, subject) do update set last_value = excluded.last_value
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
        violation: ContractViolation,
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
                it.setString(5, violation.message ?: "contract violation")
                it.setString(6, record.envelopeJson)
                it.executeUpdate()
            }
            connection.prepareStatement(
                "insert into $offsets (consumer, topic, next_offset) values (?, ?, ?) " +
                    "on conflict (consumer, topic) do update set next_offset = greatest($offsets.next_offset, excluded.next_offset)",
            ).use {
                it.setString(1, consumer)
                it.setString(2, topic)
                it.setLong(3, record.offset + 1)
                it.executeUpdate()
            }
            connection.commit()
        }
    }

    private enum class Step { APPLIED, IGNORED, DUPLICATE, STALE, DEAD_LETTER }

    private companion object {
        const val BATCH = 100
        const val FIRST_OFFSET = 1L
    }
}
