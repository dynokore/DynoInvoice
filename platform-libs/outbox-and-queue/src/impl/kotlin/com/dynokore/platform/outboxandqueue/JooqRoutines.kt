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
 * outside any tenant (ARC 4.3 step 1a).
 */
internal fun <T> TenantScope.platformTransaction(block: () -> T): T =
    transactions.outsideOpenTransaction { bindWorkload(TenantId.PLATFORM) { transactions.inTransaction(block) } }

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
