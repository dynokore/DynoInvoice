package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
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
    schema: String,
    private val clock: Clock,
    private val mapper: ObjectMapper = ObjectMapper(),
) : DeadLetterTriage {
    private val s = SqlIdentifier.require(schema, "schema")
    private val letters = "$s.${OutboxSchema.DEAD_LETTER}"
    private val triageView = "$s.${OutboxSchema.DEAD_LETTER_TRIAGE_VIEW}"
    private val queue = "$s.${QueueSchema.WORK_QUEUE}"

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

    override fun get(id: Long): DeadLetter? = metadata(DeadLetterScope(ids = setOf(id)), null, 0, 1).let { withRaw(it, DeadLetterScope()) }.singleOrNull()

    /** The letters from the non-content columns, through the triage view, in id order after [after]. */
    private fun metadata(
        scope: DeadLetterScope,
        status: DeadLetterStatus?,
        after: Long,
        limit: Int,
    ): List<DeadLetter> {
        val (where, args) = where(scope, status, after)
        return runtimeSource.connection.use { connection ->
            connection.prepareStatement("select $COLUMNS from $triageView where $where order by id limit ?").use {
                args.forEachIndexed { index, value -> it.setObject(index + 1, value) }
                it.setInt(args.size + 1, limit)
                it.executeQuery().use { rows -> buildList { while (rows.next()) add(letter(rows)) } }
            }
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
    ): Map<Long, String> = this.scope.bind(tenant) {
        this.scope.transactions.inTransaction {
            val connection = this.scope.connections.require()
            val clauses = mutableListOf("id = any(?)")
            val args = mutableListOf<Any?>(connection.createArrayOf("bigint", ids.toTypedArray()))
            filter.subject?.let {
                clauses += "${field("subject")} = ?"
                args += it
            }
            filter.entity?.let {
                clauses += "${field("entityref")} = ?"
                args += it
            }
            connection.prepareStatement("select id, raw from $letters where ${clauses.joinToString(" and ")}").use {
                args.forEachIndexed { index, value -> it.setObject(index + 1, value) }
                it.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getLong(1), rows.getString(2)) } }
            }
        }
    }

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
    ): Boolean = this.scope.bind(tenant) {
        this.scope.transactions.inTransaction {
            this.scope.connections
                .require()
                .prepareStatement(
                    """
                    insert into $queue (tenant_id, partition_key, lane, consumer, payload, enqueued_at, source_dead_letter_id)
                    values (?, ?, ?, ?, ?::jsonb, ?, ?)
                    on conflict (source_dead_letter_id) where source_dead_letter_id is not null do nothing
                    """.trimIndent(),
                ).use {
                    it.setObject(1, tenant.value)
                    it.setString(2, partitionOf(letter))
                    it.setString(3, Lane.REPLAY.name)
                    it.setString(4, consumer)
                    it.setString(5, payload(letter))
                    it.setTimestamp(6, Timestamp.from(clock.instant()))
                    it.setLong(7, letter.id)
                    it.executeUpdate() == 1
                }
        }
    }

    /** The envelope's `subject` (the Doc UUID text) so a document's replays stay in order; else the dead letter itself. */
    private fun partitionOf(letter: DeadLetter): String = try {
        mapper.readTree(letter.raw!!).get("subject")?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }
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
    ): Boolean = runtimeSource.connection.use { connection ->
        connection.prepareStatement("select $s.${OutboxSchema.DEAD_LETTER_RESOLVE}(?, ?, ?, ?, ?)").use {
            it.setLong(1, id)
            it.setString(2, status.name)
            it.setTimestamp(3, Timestamp.from(clock.instant()))
            it.setString(4, note)
            it.setString(5, replayedTo)
            it.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }
    }

    private fun where(
        scope: DeadLetterScope,
        status: DeadLetterStatus?,
        after: Long,
    ): Pair<String, List<Any>> {
        val clauses = mutableListOf("id > ?")
        val args = mutableListOf<Any>(after)

        fun add(
            clause: String,
            value: Any?,
        ) {
            if (value == null) return
            clauses += clause
            args += value
        }
        add("status = ?", status?.name)
        if (scope.ids.isNotEmpty()) {
            clauses += "id = any(?)"
            args += runtimeSource.connection.use { it.createArrayOf("bigint", scope.ids.toTypedArray()) }
        }
        add("tenant_id = ?", scope.tenant?.value)
        add("consumer = ?", scope.consumer)
        add("topic = ?", scope.topic)
        add("starts_with(reason, ?)", scope.failureClass)
        add("recorded_at >= ?", scope.recordedFrom?.let { Timestamp.from(it) })
        add("recorded_at < ?", scope.recordedBefore?.let { Timestamp.from(it) })
        if (scope.subject != null || scope.entity != null) clauses += "tenant_id is not null"
        return clauses.joinToString(" and ") to args
    }

    /** A field of the raw envelope, `null` when the raw text is not JSON (that is why it was dead-lettered). */
    private fun field(name: String) = "(case when pg_input_is_valid(raw, 'jsonb') then raw::jsonb ->> '$name' end)"

    private fun letter(rows: ResultSet) = DeadLetter(
        id = rows.getLong("id"),
        consumer = rows.getString("consumer"),
        topic = rows.getString("topic"),
        logOffset = rows.getLong("log_offset"),
        tenant = rows.getObject("tenant_id", UUID::class.java)?.let { TenantId(it) },
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
        const val COLUMNS = "id, consumer, topic, log_offset, tenant_id, reason, status, recorded_at, " +
            "resolved_at, note, replayed_to"
    }
}
