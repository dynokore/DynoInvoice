package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/**
 * The offset row, the inbox and the dead letters of the service's replay-request consumer in schema [s]
 * ([JdbcReplayResponder]): positions, dead letters and the per-topic lock each use a connection of their own, outside
 * any open transaction.
 */
internal class ResponderLedger(
    private val dsl: DSLContext,
    private val scope: TenantScope,
    private val s: String,
    private val service: String,
    private val envelopes: EnvelopeJson,
) {
    private val offsets: Table<*> = DSL.table(DSL.name(s, OutboxSchema.CONSUMER_OFFSET))
    private val inbox: Table<*> = DSL.table(DSL.name(s, OutboxSchema.INBOX))
    private val storedNext = DSL.field(DSL.name(s, OutboxSchema.CONSUMER_OFFSET, "next_offset"), SQLDataType.BIGINT)

    fun claimInbox(envelope: Envelope): Boolean =
        dsl
            .insertInto(inbox, CONSUMER, EVENT_ID, TENANT_ID)
            .values(
                DSL.`val`(service, SQLDataType.CLOB),
                DSL.`val`(envelope.id.value, SQLDataType.CLOB),
                DSL.`val`(envelope.tenant.value, SQLDataType.UUID),
            ).onConflictDoNothing()
            .execute() == 1

    /** The service's position on [topic], in a transaction of its own (the offset table holds no tenant data). */
    fun position(topic: String): Long =
        scope.platformTransaction {
            dsl
                .select(NEXT_OFFSET)
                .from(offsets)
                .where(CONSUMER.eq(service).and(TOPIC.eq(topic)))
                .fetchOne()
                ?.get(NEXT_OFFSET) ?: FIRST_OFFSET
        }

    fun lockOffset(topic: String): Long {
        dsl
            .insertInto(offsets, CONSUMER, TOPIC, NEXT_OFFSET)
            .values(
                DSL.`val`(service, SQLDataType.CLOB),
                DSL.`val`(topic, SQLDataType.CLOB),
                DSL.`val`(FIRST_OFFSET, SQLDataType.BIGINT),
            ).onConflictDoNothing()
            .execute()
        val row =
            dsl
                .select(NEXT_OFFSET)
                .from(offsets)
                .where(CONSUMER.eq(service).and(TOPIC.eq(topic)))
                .forUpdate()
                .fetchOne()
        return checkNotNull(row) { "offset row missing" }.get(NEXT_OFFSET)
    }

    fun advance(
        topic: String,
        offset: Long,
    ) {
        dsl
            .update(offsets)
            .set(NEXT_OFFSET, DSL.`val`(offset + 1, SQLDataType.BIGINT))
            .where(CONSUMER.eq(service).and(TOPIC.eq(topic)))
            .execute()
    }

    /**
     * Records the dead letter and moves the position past it in one runtime-login transaction, the letter through
     * the
     * `<svc>_sys`-owned [OutboxSchema.DEAD_LETTER_RECORD] (a letter may have no tenant, so no tenant policy admits
     * it).
     */
    fun deadLetter(
        topic: String,
        record: LogRecord,
        reason: String,
    ) {
        scope.platformTransaction {
            val letter =
                DSL.function(
                    DSL.name(s, OutboxSchema.DEAD_LETTER_RECORD),
                    SQLDataType.OTHER,
                    DSL.`val`(service, SQLDataType.CLOB),
                    DSL.`val`(topic, SQLDataType.CLOB),
                    DSL.`val`(record.offset, SQLDataType.BIGINT),
                    DSL.`val`(envelopes.tenantOf(record.envelopeJson)?.value, SQLDataType.UUID),
                    DSL.`val`(reason, SQLDataType.CLOB),
                    DSL.`val`(record.envelopeJson, SQLDataType.CLOB),
                )
            dsl.select(letter).fetch()
            dsl
                .insertInto(offsets, CONSUMER, TOPIC, NEXT_OFFSET)
                .values(
                    DSL.`val`(service, SQLDataType.CLOB),
                    DSL.`val`(topic, SQLDataType.CLOB),
                    DSL.`val`(record.offset + 1, SQLDataType.BIGINT),
                ).onConflict(CONSUMER, TOPIC)
                .doUpdate()
                .set(NEXT_OFFSET, DSL.greatest(storedNext, DSL.excluded(NEXT_OFFSET)))
                .execute()
        }
    }

    /** The per-topic transaction advisory lock, a jOOQ function call; false when another run holds it. */
    fun tryLock(topic: String): Boolean = tryXactLock("$s/replay-responder/$topic")

    /**
     * The service-wide transaction advisory lock of one answer at a time, keyed `<service>.replay-answer` (INV-Q-9087);
     * false when a run of any instance of this service holds it.
     */
    fun tryAnswerLock(): Boolean = tryXactLock("$service.replay-answer")

    private fun tryXactLock(key: String): Boolean {
        val lock = JooqRoutines.tryAdvisoryXactLock(key)
        return dsl.select(lock).fetchSingle().get(lock) == true
    }

    fun isDuplicate(envelope: Envelope): Boolean =
        scope.inTenant(TenantId.PLATFORM) {
            dsl
                .selectOne()
                .from(inbox)
                .where(CONSUMER.eq(service).and(EVENT_ID.eq(envelope.id.value)))
                .fetchOne() != null
        }

    private companion object {
        val CONSUMER = DSL.field(DSL.name("consumer"), SQLDataType.CLOB)
        val TOPIC = DSL.field(DSL.name("topic"), SQLDataType.CLOB)
        val NEXT_OFFSET = DSL.field(DSL.name("next_offset"), SQLDataType.BIGINT)
        val EVENT_ID = DSL.field(DSL.name("event_id"), SQLDataType.CLOB)
        val TENANT_ID = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
        const val FIRST_OFFSET = 1L
    }
}

/** [block] in a transaction of its own bound to [tenant], on a connection of its own. */
internal fun <T> TenantScope.inTenant(
    tenant: TenantId,
    block: () -> T,
): T =
    transactions.outsideOpenTransaction {
        bindWorkload(tenant) { transactions.inTransaction(block) }
    }
