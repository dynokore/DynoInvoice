package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.Field
import org.jooq.Record
import org.jooq.TableOptions
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.impl.SchemaImpl
import org.jooq.impl.TableImpl
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The routine calls of this module on a tenancy-jooq DSLContext (INV-T-5304): a function is named with `DSL.name`
 * (schema, function), never with `DSL.function(String, ...)`, whose unquoted name tenancy-jooq refuses.
 */
internal object JooqRoutines {
    /** `pg_catalog.pg_try_advisory_xact_lock(pg_catalog.hashtext(key))`, the transaction-scoped lock of [key]. */
    fun tryAdvisoryXactLock(key: String): Field<Boolean> =
        DSL.function(
            DSL.name("pg_catalog", "pg_try_advisory_xact_lock"),
            SQLDataType.BOOLEAN,
            hashtext(key),
        )

    /** `pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext(key))`. */
    fun advisoryXactLock(key: String): Field<Any> =
        DSL.function(DSL.name("pg_catalog", "pg_advisory_xact_lock"), SQLDataType.OTHER, hashtext(key))

    /** `<schema>.consumer_offset_lock(consumer, topic)`: the offset row's `next_offset`, row locked until the transaction ends. */
    fun consumerOffsetLock(
        schema: String,
        consumer: String,
        topic: String,
    ): Field<Long> =
        DSL.function(
            DSL.name(schema, OutboxSchema.CONSUMER_OFFSET_LOCK),
            SQLDataType.BIGINT,
            DSL.`val`(consumer, SQLDataType.CLOB),
            DSL.`val`(topic, SQLDataType.CLOB),
        )

    /** `<schema>.consumer_offset_advance(consumer, topic, next_offset)`. */
    fun consumerOffsetAdvance(
        schema: String,
        consumer: String,
        topic: String,
        nextOffset: Long,
    ): Field<Any> =
        DSL.function(
            DSL.name(schema, OutboxSchema.CONSUMER_OFFSET_ADVANCE),
            SQLDataType.OTHER,
            DSL.`val`(consumer, SQLDataType.CLOB),
            DSL.`val`(topic, SQLDataType.CLOB),
            DSL.`val`(nextOffset, SQLDataType.BIGINT),
        )

    /** `<schema>.consumer_source_seen_record(consumer, producer, source)`. */
    fun consumerSourceSeenRecord(
        schema: String,
        consumer: String,
        producer: String,
        source: String,
    ): Field<Any> =
        DSL.function(
            DSL.name(schema, OutboxSchema.CONSUMER_SOURCE_SEEN_RECORD),
            SQLDataType.OTHER,
            DSL.`val`(consumer, SQLDataType.CLOB),
            DSL.`val`(producer, SQLDataType.CLOB),
            DSL.`val`(source, SQLDataType.CLOB),
        )

    /** `<schema>.<function>(cutoff, batch_size)`: one batch of a retention purge function; the number of rows it deleted. */
    fun purgeExpired(
        schema: String,
        function: String,
        cutoff: OffsetDateTime,
        batchSize: Int,
    ): Field<Long> =
        DSL.function(
            DSL.name(schema, function),
            SQLDataType.BIGINT,
            DSL.`val`(cutoff, SQLDataType.TIMESTAMPWITHTIMEZONE),
            DSL.`val`(batchSize, SQLDataType.INTEGER),
        )

    /** `<schema>.<function>(consumer, source)` of [OutboxSchema.FULL_SYNC_DELETE]. */
    fun fullSyncDelete(
        schema: String,
        consumer: String,
        source: String,
    ): Field<Any> =
        DSL.function(
            DSL.name(schema, OutboxSchema.FULL_SYNC_DELETE),
            SQLDataType.OTHER,
            DSL.`val`(consumer, SQLDataType.CLOB),
            DSL.`val`(source, SQLDataType.CLOB),
        )

    /**
     * A read model's clear function ([OutboxSchema.readModelClearFunctionName]). [filter] is bound as text (the
     * generated function takes `text` and converts it to the filter column's type); a null [filter] calls the
     * function with no argument.
     */
    fun readModelClear(
        schema: String,
        table: String,
        filter: String?,
    ): Field<Any> {
        val name = DSL.name(schema, OutboxSchema.readModelClearFunctionName(table))
        return if (filter == null) {
            DSL.function(name, SQLDataType.OTHER)
        } else {
            DSL.function(name, SQLDataType.OTHER, DSL.`val`(filter, SQLDataType.VARCHAR))
        }
    }

    private fun hashtext(key: String): Field<Int> =
        DSL.function(DSL.name("pg_catalog", "hashtext"), SQLDataType.INTEGER, DSL.`val`(key, SQLDataType.CLOB))
}

/** A table-valued function of a schema, as jOOQ's generator emits one: [call] binds its arguments. */
abstract class FunctionTable(
    protected val schemaName: String,
    functionName: String,
    parameters: Array<Field<*>>?,
) : TableImpl<Record>(
        DSL.name(functionName),
        SchemaImpl(DSL.name(schemaName)),
        null,
        null,
        null,
        null,
        parameters,
        DSL.comment(""),
        TableOptions.function(),
        null,
    )

/** `<schema>.outbox_claim_page(after_seq, page_size, unpublished_only)` ([OutboxSchema.OUTBOX_CLAIM_PAGE]). */
class OutboxClaimPage private constructor(
    schema: String,
    parameters: Array<Field<*>>?,
) : FunctionTable(schema, OutboxSchema.OUTBOX_CLAIM_PAGE, parameters) {
    constructor(schema: String) : this(schema, null)

    val seq: Field<Long> = createField(DSL.name("seq"), SQLDataType.BIGINT, this)
    val tenantId: Field<java.util.UUID> = createField(DSL.name("tenant_id"), SQLDataType.UUID, this)
    val publishedAt: Field<OffsetDateTime> =
        createField(DSL.name("published_at"), SQLDataType.TIMESTAMPWITHTIMEZONE, this)

    fun call(
        afterSeq: Long,
        pageSize: Int,
        unpublishedOnly: Boolean,
    ): OutboxClaimPage =
        OutboxClaimPage(
            schemaName,
            arrayOf(
                DSL.`val`(afterSeq, SQLDataType.BIGINT),
                DSL.`val`(pageSize, SQLDataType.INTEGER),
                DSL.`val`(unpublishedOnly, SQLDataType.BOOLEAN),
            ),
        )
}

/** `<schema>.event_dead_letter_triage_page(...)` ([OutboxSchema.DEAD_LETTER_TRIAGE_PAGE]): the eleven non-content columns. */
class DeadLetterTriagePage private constructor(
    schema: String,
    parameters: Array<Field<*>>?,
) : FunctionTable(schema, OutboxSchema.DEAD_LETTER_TRIAGE_PAGE, parameters) {
    constructor(schema: String) : this(schema, null)

    val id: Field<Long> = createField(DSL.name("id"), SQLDataType.BIGINT, this)
    val consumer: Field<String> = createField(DSL.name("consumer"), SQLDataType.CLOB, this)
    val topic: Field<String> = createField(DSL.name("topic"), SQLDataType.CLOB, this)
    val logOffset: Field<Long> = createField(DSL.name("log_offset"), SQLDataType.BIGINT, this)
    val tenantId: Field<UUID> = createField(DSL.name("tenant_id"), SQLDataType.UUID, this)
    val reason: Field<String> = createField(DSL.name("reason"), SQLDataType.CLOB, this)
    val status: Field<String> = createField(DSL.name("status"), SQLDataType.CLOB, this)
    val recordedAt: Field<OffsetDateTime> =
        createField(DSL.name("recorded_at"), SQLDataType.TIMESTAMPWITHTIMEZONE, this)
    val resolvedAt: Field<OffsetDateTime> =
        createField(DSL.name("resolved_at"), SQLDataType.TIMESTAMPWITHTIMEZONE, this)
    val note: Field<String> = createField(DSL.name("note"), SQLDataType.CLOB, this)
    val replayedTo: Field<String> = createField(DSL.name("replayed_to"), SQLDataType.CLOB, this)

    @Suppress("LongParameterList")
    fun call(
        after: Long,
        status: String?,
        ids: Array<Long>?,
        tenant: UUID?,
        consumer: String?,
        topic: String?,
        failure: String?,
        from: OffsetDateTime?,
        before: OffsetDateTime?,
        withTenant: Boolean,
        platform: UUID,
        limit: Int,
    ): DeadLetterTriagePage =
        DeadLetterTriagePage(
            schemaName,
            arrayOf(
                DSL.`val`(after, SQLDataType.BIGINT),
                DSL.`val`(status, SQLDataType.CLOB),
                DSL.`val`(ids, SQLDataType.BIGINT.array()),
                DSL.`val`(tenant, SQLDataType.UUID),
                DSL.`val`(consumer, SQLDataType.CLOB),
                DSL.`val`(topic, SQLDataType.CLOB),
                DSL.`val`(failure, SQLDataType.CLOB),
                DSL.`val`(from, SQLDataType.TIMESTAMPWITHTIMEZONE),
                DSL.`val`(before, SQLDataType.TIMESTAMPWITHTIMEZONE),
                DSL.`val`(withTenant, SQLDataType.BOOLEAN),
                DSL.`val`(platform, SQLDataType.UUID),
                DSL.`val`(limit, SQLDataType.INTEGER),
            ),
        )
}

/** `eventlog.events_missing(topic, event_ids)` ([EventLogSchema.EVENTS_MISSING]): (`missing` text[], `cutoff`). */
class EventsMissing private constructor(
    parameters: Array<Field<*>>?,
) : FunctionTable(EventLogSchema.SCHEMA, EventLogSchema.EVENTS_MISSING, parameters) {
    constructor() : this(null)

    val missing: Field<Array<String>> = createField(DSL.name("missing"), SQLDataType.CLOB.array(), this)
    val cutoff: Field<OffsetDateTime> = createField(DSL.name("cutoff"), SQLDataType.TIMESTAMPWITHTIMEZONE, this)

    fun call(
        topic: String,
        eventIds: Array<String>,
    ): EventsMissing =
        EventsMissing(
            arrayOf(
                DSL.`val`(topic, SQLDataType.CLOB),
                DSL.`val`(eventIds, SQLDataType.CLOB.array()),
            ),
        )
}

/**
 * [block] in its own transaction under the platform binding, on a connection of its own even when the caller holds a
 * transaction (owner answer INV-Q-5609 (b)): the claims, the rate-limit counters and the dead-letter triage run
 * outside any tenant (ARC 4.3 step 1a). It is inline so that the frames of its lane-gate call belong to the calling
 * class, the recipient that asked the pool.
 */
internal inline fun <T> TenantScope.platformTransaction(crossinline block: () -> T): T =
    transactions.outsideOpenTransaction { bindWorkload(TenantId.PLATFORM) { transactions.inTransaction { block() } } }

/** `<schema>.claim_due(lane, consumer, claimed_at, reclaim_before)` ([QueueSchema.CLAIM_DUE]). */
class ClaimDue private constructor(
    schema: String,
    parameters: Array<Field<*>>?,
) : FunctionTable(schema, QueueSchema.CLAIM_DUE, parameters) {
    constructor(schema: String) : this(schema, null)

    val claimedSeq: Field<Long> = createField(DSL.name("claimed_seq"), SQLDataType.BIGINT, this)
    val claimedTenant: Field<UUID> = createField(DSL.name("claimed_tenant"), SQLDataType.UUID, this)
    val claimedPartition: Field<String> = createField(DSL.name("claimed_partition"), SQLDataType.CLOB, this)

    fun call(
        lane: String,
        consumer: String?,
        claimedAt: OffsetDateTime,
        reclaimBefore: OffsetDateTime,
    ): ClaimDue =
        ClaimDue(
            schemaName,
            arrayOf(
                DSL.`val`(lane, SQLDataType.CLOB),
                DSL.`val`(consumer, SQLDataType.CLOB),
                DSL.`val`(claimedAt, SQLDataType.TIMESTAMPWITHTIMEZONE),
                DSL.`val`(reclaimBefore, SQLDataType.TIMESTAMPWITHTIMEZONE),
            ),
        )
}

/** `eventlog.claim(topic, after_offset, limit)` ([EventLogSchema.CLAIM]): (`log_offset`, `tenant_id`, `inserted_at`). */
class EventLogClaim private constructor(
    parameters: Array<Field<*>>?,
) : FunctionTable(EventLogSchema.SCHEMA, EventLogSchema.CLAIM, parameters) {
    constructor() : this(null)

    val logOffset: Field<Long> = createField(DSL.name("log_offset"), SQLDataType.BIGINT, this)
    val tenantId: Field<UUID> = createField(DSL.name("tenant_id"), SQLDataType.UUID, this)
    val insertedAt: Field<OffsetDateTime> =
        createField(DSL.name("inserted_at"), SQLDataType.TIMESTAMPWITHTIMEZONE, this)

    fun call(
        topic: String,
        afterOffset: Long,
        limit: Int,
    ): EventLogClaim =
        EventLogClaim(
            arrayOf(
                DSL.`val`(topic, SQLDataType.CLOB),
                DSL.`val`(afterOffset, SQLDataType.BIGINT),
                DSL.`val`(limit, SQLDataType.INTEGER),
            ),
        )
}

/** `eventlog.purge_batch()` ([EventLogSchema.PURGE_BATCH]): one row per topic of one purge batch. */
class EventLogPurgeBatch : FunctionTable(EventLogSchema.SCHEMA, EventLogSchema.PURGE_BATCH, emptyArray()) {
    val topic: Field<String> = createField(DSL.name("purged_topic"), SQLDataType.CLOB, this)
    val content: Field<Boolean> = createField(DSL.name("purged_content"), SQLDataType.BOOLEAN, this)
    val period: Field<org.jooq.types.DayToSecond> =
        createField(DSL.name("purged_period"), SQLDataType.INTERVALDAYTOSECOND, this)
    val cutoff: Field<OffsetDateTime> =
        createField(DSL.name("purged_cutoff"), SQLDataType.TIMESTAMPWITHTIMEZONE, this)
    val rows: Field<Long> = createField(DSL.name("purged_rows"), SQLDataType.BIGINT, this)
    val watermark: Field<Long> = createField(DSL.name("purged_watermark"), SQLDataType.BIGINT, this)
}
