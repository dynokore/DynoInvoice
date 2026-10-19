package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * [DeadLetterTriage] over the `event_dead_letter` table ([OutboxSchema]). Dead letters keep whole rejected envelopes
 * (content) under forced row-level security. It lists them through the sys-owned table function
 * [OutboxSchema.DEAD_LETTER_TRIAGE_PAGE] over the `<svc>_sys`-owned view [OutboxSchema.DEAD_LETTER_TRIAGE_VIEW] (the
 * non-content columns only, ARC 4.3 step 1a), in a platform transaction, and resolves them through the definer function
 * [OutboxSchema.DEAD_LETTER_RESOLVE] (INV-Q-3953); `raw` is
 * read, and filtered by subject or entity, in the letter's tenant session under the tenant policy (INV-T-2997). A
 * letter without a tenant is triaged from metadata alone.
 *
 * Replay writes into a tenant's queue, which only a tenant session may do: for each dead letter it binds the
 * dead letter's tenant ([TenantScope], ARC-21), inserts one replay-lane item addressed to the named consumer in a
 * tenant transaction, then marks the dead letter through [OutboxSchema.DEAD_LETTER_RESOLVE]. The queue item is unique
 * per dead
 * letter, so a crash between the two steps is repaired by running the replay again.
 */
class JdbcDeadLetterTriage(
    private val scope: TenantScope,
    private val dsl: DSLContext,
    schema: String,
    private val clock: Clock,
    mapper: ObjectMapper = ObjectMapper(),
) : DeadLetterTriage {
    private val s = SqlIdentifier.require(schema, "schema")
    private val letters: Table<*> = DSL.table(DSL.name(s, OutboxSchema.DEAD_LETTER))
    private val replayQueue =
        DeadLetterReplayQueue(scope, dsl, DSL.table(DSL.name(s, QueueSchema.WORK_QUEUE)), clock, mapper)

    override fun list(
        scope: DeadLetterScope,
        status: DeadLetterStatus,
        limit: Int,
    ): List<DeadLetter> {
        require(limit in 1..MAX_LIMIT) { "limit must be 1..$MAX_LIMIT" }
        val readsRaw = scope.subject != null || scope.entity != null
        val found = mutableListOf<DeadLetter>()
        var after = 0L
        var more = true
        while (more && found.size < limit) {
            // Without a raw filter one page is the answer; with one, pages are read until `limit` letters match.
            val pageSize = if (readsRaw) MAX_LIMIT else limit
            val page = metadata(scope, status, after, pageSize)
            if (page.isNotEmpty()) {
                after = page.last().id
                found += withRaw(page, scope)
            }
            more = page.size >= pageSize
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
        this.scope.platformTransaction {
            val page = DeadLetterTriagePage(s)
            val call =
                page.call(
                    after = after,
                    status = status?.name,
                    ids = scope.ids.takeIf { it.isNotEmpty() }?.toTypedArray(),
                    tenant = scope.tenant?.value,
                    consumer = scope.consumer,
                    topic = scope.topic,
                    failure = scope.failureClass,
                    from = scope.recordedFrom?.atOffset(ZoneOffset.UTC),
                    before = scope.recordedBefore?.atOffset(ZoneOffset.UTC),
                    withTenant = scope.subject != null || scope.entity != null,
                    platform = TenantId.PLATFORM.value,
                    limit = limit,
                )
            dsl.selectFrom(call).fetch().map { letter(it, call) }
        }

    /**
     * Fills `raw` of each letter with a tenant by reading it in that tenant's session, where the tenant policy applies
     * (the triage view never exposes it). A letter without a tenant keeps `raw = null`. When [filter] gives a subject
     * or
     * entity, only the letters whose raw envelope matches are returned, and a letter without a tenant never does.
     */
    private fun withRaw(
        page: List<DeadLetter>,
        filter: DeadLetterScope,
    ): List<DeadLetter> {
        val readsRaw = filter.subject != null || filter.entity != null
        val raws = mutableMapOf<Long, String>()
        page
            .filter { it.tenant != null }
            .groupBy {
                it.tenant ?: throw NullPointerException(
                    "tenant is null",
                )
            }.forEach { (tenant, group) ->
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
            if (replayQueue.enqueue(tenant, letter, consumer)) queued++ else already++
            resolve(letter.id, DeadLetterStatus.REPLAYED, null, consumer)
        }
        return ReplayResult(queued, skipped, already)
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

    private fun letter(
        row: Record,
        page: DeadLetterTriagePage,
    ) = DeadLetter(
        id = row.get(page.id),
        consumer = row.get(page.consumer),
        topic = row.get(page.topic),
        logOffset = row.get(page.logOffset),
        tenant = row.get(page.tenantId)?.let { TenantId(it) }?.takeUnless { it.isPlatform },
        reason = row.get(page.reason),
        raw = null,
        status = DeadLetterStatus.valueOf(row.get(page.status)),
        recordedAt = row.get(page.recordedAt).toInstant(),
        resolvedAt = row.get(page.resolvedAt)?.toInstant(),
        note = row.get(page.note),
        replayedTo = row.get(page.replayedTo),
    )

    private companion object {
        const val MAX_LIMIT = 1000
        const val SUBJECT_KEY = "subject"
        const val ENTITY_KEY = "entityref"
        val ID = DSL.field(DSL.name("id"), SQLDataType.BIGINT)
        val RAW = DSL.field(DSL.name("raw"), SQLDataType.CLOB)
    }
}
