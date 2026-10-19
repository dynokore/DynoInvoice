package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.outboxandqueue.InboxColumns.CONSUMER
import com.dynokore.platform.outboxandqueue.InboxColumns.EVENT_ID
import com.dynokore.platform.outboxandqueue.InboxColumns.LAST_VALUE
import com.dynokore.platform.outboxandqueue.InboxColumns.SOURCE
import com.dynokore.platform.outboxandqueue.InboxColumns.SUBJECT
import com.dynokore.platform.outboxandqueue.InboxColumns.TENANT_ID
import org.jooq.DSLContext
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/** What applying one envelope did ([JdbcInboxConsumer]). */
internal enum class Step { APPLIED, IGNORED, DUPLICATE, STALE, DEAD_LETTER }

/**
 * The inbox and the per-source sequence of one consumer in its schema [s], inside the record's tenant transaction:
 * the dedup by event id, the `docsequence` rule and the handler call (steps 3 to 5 of [JdbcInboxConsumer]).
 */
internal class InboxLedger(
    private val dsl: DSLContext,
    private val s: String,
    private val consumer: String,
    private val trackSources: Boolean,
    private val recordTypes: Set<String>,
    private val handler: EventHandler,
) {
    private val inbox: Table<*> = DSL.table(DSL.name(s, OutboxSchema.INBOX))
    private val sequences: Table<*> = DSL.table(DSL.name(s, OutboxSchema.CONSUMER_SEQUENCE))

    fun apply(envelope: Envelope): Step {
        if (!claimInbox(envelope)) return Step.DUPLICATE
        if (trackSources &&
            envelope.type != ReplayProtocol.REPLAY_COMPLETED
        ) {
            recordSource(envelope)
        }
        // A record-topic event is deduped by event id only: no docsequence check, no held row (INV-Q-3809).
        val sequence = envelope.docSequence?.takeUnless { envelope.type in recordTypes }
        return if (sequence != null && sequence <= heldSequence(envelope)) Step.STALE else handle(envelope, sequence)
    }

    private fun handle(
        envelope: Envelope,
        sequence: Long?,
    ): Step {
        val outcome = handler.handle(envelope)
        if (sequence != null) recordSequence(envelope, sequence)
        return when (outcome) {
            HandleOutcome.APPLIED -> Step.APPLIED
            HandleOutcome.IGNORED -> Step.IGNORED
        }
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
     * INV-Q-3669: the source of an event on an 'every service' topic joins the consumer's seen set (a completion
     * marker
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
}
