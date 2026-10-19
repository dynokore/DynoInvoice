package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.ZoneOffset

/** Puts one replay-lane item per dead letter into a tenant's queue ([JdbcDeadLetterTriage.replay]). */
internal class DeadLetterReplayQueue(
    private val scope: TenantScope,
    private val dsl: DSLContext,
    private val queue: Table<*>,
    private val clock: Clock,
    private val mapper: ObjectMapper,
) {
    fun enqueue(
        tenant: TenantId,
        letter: DeadLetter,
        consumer: String,
    ): Boolean =
        scope.bindWorkload(tenant) {
            scope.transactions.inTransaction {
                dsl
                    .insertInto(queue)
                    .columns(TENANT_ID, PARTITION_KEY, LANE, CONSUMER, PAYLOAD, ENQUEUED_AT, SOURCE_DEAD_LETTER_ID)
                    .values(
                        DSL.`val`(tenant.value, SQLDataType.UUID),
                        DSL.`val`(partitionOf(letter), SQLDataType.CLOB),
                        DSL.`val`(Lane.REPLAY.name, SQLDataType.CLOB),
                        DSL.`val`(consumer, SQLDataType.CLOB),
                        DSL.`val`(payload(letter), SQLDataType.JSONB),
                        DSL.`val`(clock.instant().atOffset(ZoneOffset.UTC), SQLDataType.TIMESTAMPWITHTIMEZONE),
                        DSL.`val`(letter.id, SQLDataType.BIGINT),
                    ).onConflict(SOURCE_DEAD_LETTER_ID)
                    .where(SOURCE_DEAD_LETTER_ID.isNotNull)
                    .doNothing()
                    .execute() == 1
            }
        }

    /**
     * The envelope's `subject` (the Doc UUID text) so a document's replays stay in order; else the dead letter itself.
     */
    private fun partitionOf(letter: DeadLetter): String =
        try {
            mapper
                .readTree((letter.raw ?: throw NullPointerException("letter.raw is null")))
                .get("subject")
                ?.takeIf { it.isString }
                ?.asString()
                ?.takeIf { it.isNotBlank() }
        } catch (_: JacksonException) {
            null
        } ?: "dead-letter-${letter.id}"

    private fun payload(letter: DeadLetter): String {
        val node = mapper.createObjectNode()
        node.put("deadLetterId", letter.id)
        node.put("topic", letter.topic)
        node.put("raw", checkNotNull(letter.raw) { "a replay needs the raw envelope" })
        return mapper.writeValueAsString(node)
    }

    private companion object {
        val TENANT_ID = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
        val PARTITION_KEY = DSL.field(DSL.name("partition_key"), SQLDataType.CLOB)
        val LANE = DSL.field(DSL.name("lane"), SQLDataType.CLOB)
        val CONSUMER = DSL.field(DSL.name("consumer"), SQLDataType.CLOB)
        val PAYLOAD = DSL.field(DSL.name("payload"), SQLDataType.JSONB)
        val ENQUEUED_AT = DSL.field(DSL.name("enqueued_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
        val SOURCE_DEAD_LETTER_ID = DSL.field(DSL.name("source_dead_letter_id"), SQLDataType.BIGINT)
    }
}
