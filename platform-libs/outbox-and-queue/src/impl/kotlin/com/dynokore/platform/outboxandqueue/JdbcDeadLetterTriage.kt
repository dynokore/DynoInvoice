package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * [DeadLetterTriage] over the `event_dead_letter` table ([OutboxSchema]). Dead letters keep whole rejected envelopes
 * (content) under forced row-level security. [runtimeSource], a connection of the service's runtime login, lists them
 * through the `<svc>_sys`-owned view [OutboxSchema.DEAD_LETTER_TRIAGE_VIEW] (the non-content columns only, ARC 4.3
 * step 1a) and resolves them through the definer function [OutboxSchema.DEAD_LETTER_RESOLVE] (INV-Q-3953); `raw` is
 * read, and filtered by subject or entity, in the letter's tenant session under the tenant policy (INV-T-2997). A
 * letter without a tenant is triaged from metadata alone.
 *
 * Replay writes into a tenant's queue, which only a tenant session may do: for each dead letter it binds the
 * dead letter's tenant ([TenantScope], ARC-21), inserts one replay-lane item addressed to the named consumer in a
 * tenant transaction, then marks the dead letter through [OutboxSchema.DEAD_LETTER_RESOLVE]. The queue item is unique per dead
 * letter, so a crash between the two steps is repaired by running the replay again.
 */
class JdbcDeadLetterTriage(
    private val runtimeSource: DataSource,
    private val scope: TenantScope,
    private val dsl: DSLContext,
    schema: String,
    private val clock: Clock,
    private val mapper: ObjectMapper = ObjectMapper(),
) : DeadLetterTriage {
    private val s = SqlIdentifier.require(schema, "schema")
    private val letters: Table<*> = DSL.table(DSL.name(s, OutboxSchema.DEAD_LETTER))
    private val triageView = "$s.${OutboxSchema.DEAD_LETTER_TRIAGE_VIEW}"
    private val queue: Table<*> = DSL.table(DSL.name(s, QueueSchema.WORK_QUEUE))

    override fun list(
        scope: DeadLetterScope,
        status: DeadLetterStatus,
        limit: Int,
    ): List<DeadLetter> {
        require(limit in 1..MAX_LIMIT) { "limit must be 1..$MAX_LIMIT" }
        val readsRaw = scope.subject != null || scope.entity != null
        val found = mutableListOf<DeadLetter>()
        var after = 0L
        while (found.size < limit) {
            // Without a raw filter one page is the answer; with one, pages are read until `limit` letters match.
            val pageSize = if (readsRaw) MAX_LIMIT else limit
            val page = metadata(scope, status, after, pageSize)
            if (page.isEmpty()) break
            after = page.last().id
            found += withRaw(page, scope)
            if (page.size < pageSize) break
        }
        return found.take(limit)
    }

    override fun get(id: Long): DeadLetter? =
        metadata(DeadLetterScope(ids = setOf(id)), null, 0, 1)
            .let {
                withRaw(it, DeadLetterScope())
            }.singleOrNull()

    /**
     * The letters from the non-content columns, through the triage view, in id order after [after]. One static
     * statement: each filter is a bound parameter, and a null parameter leaves its filter out (INV-Q-4036).
     */
    private fun metadata(
        scope: DeadLetterScope,
        status: DeadLetterStatus?,
        after: Long,
        limit: Int,
    ): List<DeadLetter> =
        runtimeSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    with p as (
                        select ?::bigint as p_after, ?::text as p_status, ?::bigint[] as p_ids, ?::uuid as p_tenant,
                            ?::text as p_consumer, ?::text as p_topic, ?::text as p_failure, ?::timestamptz as p_from,
                            ?::timestamptz as p_before, ?::boolean as p_with_tenant, ?::uuid as p_platform
                    )
                    select $COLUMNS from $triageView, p
                    where id > p_after
                      and (p_status is null or status = p_status)
                      and (p_ids is null or id = any(p_ids))
                      and (p_tenant is null or tenant_id = p_tenant)
                      and (p_consumer is null or consumer = p_consumer)
                      and (p_topic is null or topic = p_topic)
                      and (p_failure is null or starts_with(reason, p_failure))
                      and (p_from is null or recorded_at >= p_from)
                      and (p_before is null or recorded_at < p_before)
                      and (not p_with_tenant or tenant_id <> p_platform)
                    order by id limit ?
                    """.trimIndent(),
                ).use {
                    it.setLong(1, after)
                    it.setString(2, status?.name)
                    val ids = scope.ids.takeIf { ids -> ids.isNotEmpty() }
                    it.setArray(3, ids?.let { connection.createArrayOf("bigint", ids.toTypedArray()) })
                    it.setObject(4, scope.tenant?.value)
                    it.setString(5, scope.consumer)
                    it.setString(6, scope.topic)
                    it.setString(7, scope.failureClass)
                    it.setTimestamp(8, scope.recordedFrom?.let { from -> Timestamp.from(from) })
                    it.setTimestamp(9, scope.recordedBefore?.let { before -> Timestamp.from(before) })
                    it.setBoolean(10, scope.subject != null || scope.entity != null)
                    it.setObject(11, TenantId.PLATFORM.value)
                    it.setInt(12, limit)
                    it.executeQuery().use { rows -> buildList { while (rows.next()) add(letter(rows)) } }
                }
        }

    /**
     * Fills `raw` of each letter with a tenant by reading it in that tenant's session, where the tenant policy applies
     * (the triage view never exposes it). A letter without a tenant keeps `raw = null`. When [filter] gives a subject or
     * entity, only the letters whose raw envelope matches are returned, and a letter without a tenant never does.
     */
    private fun withRaw(
        page: List<DeadLetter>,
        filter: DeadLetterScope,
    ): List<DeadLetter> {
        val readsRaw = filter.subject != null || filter.entity != null
        val raws = mutableMapOf<Long, String>()
        page.filter { it.tenant != null }.groupBy { it.tenant!! }.forEach { (tenant, group) ->
            raws += rawsOf(tenant, group.map { it.id }, filter)
        }
        return page.mapNotNull { letter ->
            val raw = raws[letter.id]
            when {
                letter.tenant == null -> if (readsRaw) null else letter

                raw == null -> null

                // not visible in the tenant session, or not matching the raw filter
                else -> letter.copy(raw = raw)
            }
        }
    }

    private fun rawsOf(
        tenant: TenantId,
        ids: List<Long>,
        filter: DeadLetterScope,
    ): Map<Long, String> =
        this.scope.bindWorkload(tenant) {
            this.scope.transactions.inTransaction {
                val conditions = mutableListOf<Condition>(ID.`in`(ids))
                filter.subject?.let { conditions += jsonText(SUBJECT_KEY).eq(it) }
                filter.entity?.let { conditions += jsonText(ENTITY_KEY).eq(it) }
                dsl
                    .select(ID, RAW)
                    .from(letters)
                    .where(conditions)
                    .fetch()
                    .associate { it.get(ID) to it.get(RAW) }
            }
        }

    /** A field of the raw envelope, `null` when the raw text is not JSON (that is why it was dead-lettered). */
    private fun jsonText(key: String) =
        DSL
            .`when`(
                DSL.function(
                    DSL.name("pg_catalog", "pg_input_is_valid"),
                    SQLDataType.BOOLEAN,
                    RAW,
                    DSL.inline("jsonb"),
                ),
                DSL.jsonbGetAttributeAsText(RAW.cast(SQLDataType.JSONB), key),
            ).otherwise(DSL.castNull(SQLDataType.CLOB))

    override fun discard(
        id: Long,
        note: String,
    ): Boolean {
        require(note.isNotBlank()) { "a discard needs a note" }
        return resolve(id, DeadLetterStatus.DISCARDED, note, null)
    }

    override fun replay(
        scope: DeadLetterScope,
        consumer: String,
    ): ReplayResult {
        require(scope.isNarrowed) { "a replay needs at least one scope criterion" }
        require(consumer.isNotBlank()) { "a replay names one consumer" }
        var queued = 0
        var skipped = 0
        var already = 0
        for (letter in list(scope, DeadLetterStatus.OPEN, MAX_LIMIT)) {
            val tenant = letter.tenant
            if (tenant == null) {
                skipped++
                continue
            }
            if (enqueueReplay(tenant, letter, consumer)) queued++ else already++
            resolve(letter.id, DeadLetterStatus.REPLAYED, null, consumer)
        }
        return ReplayResult(queued, skipped, already)
    }

    private fun enqueueReplay(
        tenant: TenantId,
        letter: DeadLetter,
        consumer: String,
    ): Boolean =
        this.scope.bindWorkload(tenant) {
            this.scope.transactions.inTransaction {
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

    /** The envelope's `subject` (the Doc UUID text) so a document's replays stay in order; else the dead letter itself. */
    private fun partitionOf(letter: DeadLetter): String =
        try {
            mapper
                .readTree(letter.raw!!)
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

    private fun resolve(
        id: Long,
        status: DeadLetterStatus,
        note: String?,
        replayedTo: String?,
    ): Boolean {
        val resolved =
            DSL.function(
                DSL.name(s, OutboxSchema.DEAD_LETTER_RESOLVE),
                SQLDataType.BOOLEAN,
                DSL.`val`(id, SQLDataType.BIGINT),
                DSL.`val`(status.name, SQLDataType.CLOB),
                DSL.`val`(clock.instant().atOffset(ZoneOffset.UTC), SQLDataType.TIMESTAMPWITHTIMEZONE),
                DSL.`val`(note, SQLDataType.CLOB),
                DSL.`val`(replayedTo, SQLDataType.CLOB),
            )
        return scope.platformTransaction { dsl.select(resolved).fetchOne()?.get(resolved) == true }
    }

    private fun letter(rows: ResultSet) =
        DeadLetter(
            id = rows.getLong("id"),
            consumer = rows.getString("consumer"),
            topic = rows.getString("topic"),
            logOffset = rows.getLong("log_offset"),
            tenant = rows.getObject("tenant_id", UUID::class.java)?.let { TenantId(it) }?.takeUnless { it.isPlatform },
            reason = rows.getString("reason"),
            raw = null,
            status = DeadLetterStatus.valueOf(rows.getString("status")),
            recordedAt = rows.getTimestamp("recorded_at").toInstant(),
            resolvedAt = rows.getTimestamp("resolved_at")?.toInstant(),
            note = rows.getString("note"),
            replayedTo = rows.getString("replayed_to"),
        )

    private companion object {
        const val MAX_LIMIT = 1000
        const val COLUMNS =
            "id, consumer, topic, log_offset, tenant_id, reason, status, recorded_at, " +
                "resolved_at, note, replayed_to"

        const val SUBJECT_KEY = "subject"
        const val ENTITY_KEY = "entityref"
        val ID = DSL.field(DSL.name("id"), SQLDataType.BIGINT)
        val RAW = DSL.field(DSL.name("raw"), SQLDataType.CLOB)
        val TENANT_ID = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
        val PARTITION_KEY = DSL.field(DSL.name("partition_key"), SQLDataType.CLOB)
        val LANE = DSL.field(DSL.name("lane"), SQLDataType.CLOB)
        val CONSUMER = DSL.field(DSL.name("consumer"), SQLDataType.CLOB)
        val PAYLOAD = DSL.field(DSL.name("payload"), SQLDataType.JSONB)
        val ENQUEUED_AT = DSL.field(DSL.name("enqueued_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
        val SOURCE_DEAD_LETTER_ID = DSL.field(DSL.name("source_dead_letter_id"), SQLDataType.BIGINT)
    }
}
