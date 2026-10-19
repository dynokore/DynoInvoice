package com.dynokore.platform.outboxandqueue

import org.jooq.DSLContext
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/** The position of a consumer on a topic, as [JdbcConsumerReplay] reads and moves it in the caller's transaction. */
internal class ReplayOffsets(
    private val dsl: DSLContext,
    private val offsets: Table<*>,
) {
    fun nextOffset(
        consumer: String,
        topic: String,
        lock: Boolean,
    ): Long {
        val query = dsl.select(NEXT_OFFSET).from(offsets).where(CONSUMER.eq(consumer).and(TOPIC.eq(topic)))
        return (if (lock) query.forUpdate() else query).fetchOne(NEXT_OFFSET) ?: FIRST_OFFSET
    }

    fun moveTo(
        consumer: String,
        topic: String,
        next: Long,
    ) {
        dsl
            .insertInto(offsets)
            .columns(CONSUMER, TOPIC, NEXT_OFFSET)
            .values(consumer, topic, next)
            .onConflict(CONSUMER, TOPIC)
            .doUpdate()
            .set(NEXT_OFFSET, DSL.excluded(NEXT_OFFSET))
            .set(ATTEMPTS, 0)
            .set(INFRA_FAILURES, 0)
            .set(FIRST_INFRA_FAILURE_AT, DSL.`val`(null, SQLDataType.TIMESTAMPWITHTIMEZONE))
            .set(RETRY_NOT_BEFORE, DSL.`val`(null, SQLDataType.TIMESTAMPWITHTIMEZONE))
            .execute()
    }

    private companion object {
        const val FIRST_OFFSET = 1L
        val CONSUMER = DSL.field(DSL.name("consumer"), SQLDataType.CLOB.notNull())
        val TOPIC = DSL.field(DSL.name("topic"), SQLDataType.CLOB.notNull())
        val NEXT_OFFSET = DSL.field(DSL.name("next_offset"), SQLDataType.BIGINT.notNull())
        val ATTEMPTS = DSL.field(DSL.name("attempts"), SQLDataType.INTEGER)
        val INFRA_FAILURES = DSL.field(DSL.name("infra_failures"), SQLDataType.INTEGER)
        val FIRST_INFRA_FAILURE_AT = DSL.field(DSL.name("first_infra_failure_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
        val RETRY_NOT_BEFORE = DSL.field(DSL.name("retry_not_before"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    }
}
