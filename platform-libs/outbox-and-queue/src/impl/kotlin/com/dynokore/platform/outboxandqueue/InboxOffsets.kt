package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.outboxandqueue.InboxColumns.ATTEMPTS
import com.dynokore.platform.outboxandqueue.InboxColumns.CONSUMER
import com.dynokore.platform.outboxandqueue.InboxColumns.FIRST_INFRA_FAILURE_AT
import com.dynokore.platform.outboxandqueue.InboxColumns.INFRA_FAILURES
import com.dynokore.platform.outboxandqueue.InboxColumns.NEXT_OFFSET
import com.dynokore.platform.outboxandqueue.InboxColumns.RETRY_NOT_BEFORE
import com.dynokore.platform.outboxandqueue.InboxColumns.TOPIC
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** What the offset row says about the delivery of its next record. */
internal class AttemptState(
    val attempts: Int,
    val infraFailures: Int,
    val firstInfra: Instant?,
)

/**
 * The offset row and the dead letters of one (consumer, topic) in the consumer's schema [s] ([JdbcInboxConsumer]):
 * outside any tenant transaction, in transactions of their own under the platform binding, and the offset moving
 * inside the record's tenant transaction through definer routines.
 */
internal class InboxOffsets(
    private val dsl: DSLContext,
    private val scope: TenantScope,
    private val s: String,
    private val consumer: String,
    private val topic: String,
    private val json: EnvelopeJson,
) {
    private val offsets: Table<*> = DSL.table(DSL.name(s, OutboxSchema.CONSUMER_OFFSET))

    /**
     * The offset row's own columns, qualified by the table name: in `ON CONFLICT DO UPDATE` a bare name is
     * ambiguous.
     */
    private val storedNext = stored("next_offset", NEXT_OFFSET)
    private val storedAttempts = stored("attempts", ATTEMPTS)

    fun position(): Pair<Long, Instant?> =
        platform {
            dsl
                .select(NEXT_OFFSET, RETRY_NOT_BEFORE)
                .from(offsets)
                .where(CONSUMER.eq(consumer).and(TOPIC.eq(topic)))
                .fetchOne()
                ?.let { it.get(NEXT_OFFSET) to it.get(RETRY_NOT_BEFORE)?.toInstant() } ?: (FIRST_OFFSET to null)
        }

    /** In its own transaction, before the handler runs: counts this delivery on the offset row (INV-Q-3108). */
    fun beginAttempt(offset: Long): AttemptState? =
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
    fun schedule(
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

    // The offset and source-seen tables are `PLATFORM_SCOPED`, which tenancy-jooq writes only under the platform
    // binding, while the offset moves in the record's tenant transaction (INV-AC-2205): these two calls go
    // through
    // sys-owned definer routines (INV-Q-5873).
    fun lockOffset(): Long = dsl.select(JooqRoutines.consumerOffsetLock(s, consumer, topic)).fetchSingle().value1()

    fun advance(offset: Long) {
        dsl.select(JooqRoutines.consumerOffsetAdvance(s, consumer, topic, offset + 1)).fetch()
    }

    /**
     * Records the dead letter and moves the offset past it in one platform transaction, the letter through the
     * `<svc>_sys`-owned [OutboxSchema.DEAD_LETTER_RECORD] (a letter may have no tenant, so no tenant policy admits
     * it).
     * The offset upsert resets attempts, infra_failures, first_infra_failure_at and retry_not_before when the new
     * offset is greater than the stored one and keeps the stored value otherwise.
     */
    fun deadLetter(
        record: LogRecord,
        reason: String,
    ) {
        platform {
            recordLetter(record, reason)
            moveOffsetPast(record.offset)
        }
    }

    private fun recordLetter(
        record: LogRecord,
        reason: String,
    ) {
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
    }

    private fun moveOffsetPast(offset: Long) {
        val moved = DSL.excluded(NEXT_OFFSET).gt(storedNext)
        dsl
            .insertInto(offsets, CONSUMER, TOPIC, NEXT_OFFSET)
            .values(
                DSL.`val`(consumer, SQLDataType.CLOB),
                DSL.`val`(topic, SQLDataType.CLOB),
                DSL.`val`(offset + 1, SQLDataType.BIGINT),
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

    /**
     * [block] in its own transaction under the platform binding: the offset and the dead letters are no tenant's.
     * Inline, so the frames of the lane-gate call belong to this class and not to the routines file.
     */
    private inline fun <T> platform(crossinline block: () -> T): T = scope.platformTransaction { block() }

    private fun <T> stored(
        column: String,
        like: Field<T>,
    ): Field<T> = DSL.field(DSL.name(s, OutboxSchema.CONSUMER_OFFSET, column), like.dataType)

    private companion object {
        const val FIRST_OFFSET = 1L

        fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)
    }
}
