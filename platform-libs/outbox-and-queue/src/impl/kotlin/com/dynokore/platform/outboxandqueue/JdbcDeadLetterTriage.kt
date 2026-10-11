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
 * (content) under forced row-level security with a policy for the worker role only, so triage runs on
 * [workerSource], a worker-role connection; the app role and any tenant session cannot read them.
 *
 * Replay writes into a tenant's queue, which only a tenant session may do: for each dead letter it binds the
 * dead letter's tenant ([TenantScope], ARC-21), inserts one replay-lane item addressed to the named consumer in a
 * tenant transaction, then marks the dead letter on the worker connection. The queue item is unique per dead
 * letter, so a crash between the two steps is repaired by running the replay again.
 */
class JdbcDeadLetterTriage(
    private val workerSource: DataSource,
    private val scope: TenantScope,
    schema: String,
    private val clock: Clock,
    private val mapper: ObjectMapper = ObjectMapper(),
) : DeadLetterTriage {
    private val s = SqlIdentifier.require(schema, "schema")
    private val letters = "$s.${OutboxSchema.DEAD_LETTER}"
    private val queue = "$s.${QueueSchema.WORK_QUEUE}"

    override fun list(
        scope: DeadLetterScope,
        status: DeadLetterStatus,
        limit: Int,
    ): List<DeadLetter> {
        require(limit in 1..MAX_LIMIT) { "limit must be 1..$MAX_LIMIT" }
        val (where, args) = where(scope, status)
        return workerSource.connection.use { connection ->
            connection.prepareStatement("select $COLUMNS from $letters where $where order by id limit ?").use {
                args.forEachIndexed { index, value -> it.setObject(index + 1, value) }
                it.setInt(args.size + 1, limit)
                it.executeQuery().use { rows -> buildList { while (rows.next()) add(letter(rows)) } }
            }
        }
    }

    override fun get(id: Long): DeadLetter? = workerSource.connection.use { connection ->
        connection.prepareStatement("select $COLUMNS from $letters where id = ?").use {
            it.setLong(1, id)
            it.executeQuery().use { rows -> if (rows.next()) letter(rows) else null }
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
        mapper.readTree(letter.raw).get("subject")?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }
    } catch (_: JacksonException) {
        null
    } ?: "dead-letter-${letter.id}"

    private fun payload(letter: DeadLetter): String {
        val node = mapper.createObjectNode()
        node.put("deadLetterId", letter.id)
        node.put("topic", letter.topic)
        node.put("raw", letter.raw)
        return mapper.writeValueAsString(node)
    }

    private fun resolve(
        id: Long,
        status: DeadLetterStatus,
        note: String?,
        replayedTo: String?,
    ): Boolean = workerSource.connection.use { connection ->
        connection
            .prepareStatement(
                "update $letters set status = ?, resolved_at = ?, note = ?, replayed_to = ? " +
                    "where id = ? and status = 'OPEN'",
            ).use {
                it.setString(1, status.name)
                it.setTimestamp(2, Timestamp.from(clock.instant()))
                it.setString(3, note)
                it.setString(4, replayedTo)
                it.setLong(5, id)
                it.executeUpdate() == 1
            }
    }

    private fun where(
        scope: DeadLetterScope,
        status: DeadLetterStatus,
    ): Pair<String, List<Any>> {
        val clauses = mutableListOf("status = ?")
        val args = mutableListOf<Any>(status.name)

        fun add(
            clause: String,
            value: Any?,
        ) {
            if (value == null) return
            clauses += clause
            args += value
        }
        if (scope.ids.isNotEmpty()) {
            clauses += "id = any(?)"
            args += workerSource.connection.use { it.createArrayOf("bigint", scope.ids.toTypedArray()) }
        }
        add("tenant_id = ?", scope.tenant?.value)
        add("consumer = ?", scope.consumer)
        add("topic = ?", scope.topic)
        add("starts_with(reason, ?)", scope.failureClass)
        add("${field("subject")} = ?", scope.subject)
        add("${field("entityref")} = ?", scope.entity)
        add("recorded_at >= ?", scope.recordedFrom?.let { Timestamp.from(it) })
        add("recorded_at < ?", scope.recordedBefore?.let { Timestamp.from(it) })
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
        raw = rows.getString("raw"),
        status = DeadLetterStatus.valueOf(rows.getString("status")),
        recordedAt = rows.getTimestamp("recorded_at").toInstant(),
        resolvedAt = rows.getTimestamp("resolved_at")?.toInstant(),
        note = rows.getString("note"),
        replayedTo = rows.getString("replayed_to"),
    )

    private companion object {
        const val MAX_LIMIT = 1000
        const val COLUMNS = "id, consumer, topic, log_offset, tenant_id, reason, raw, status, recorded_at, " +
            "resolved_at, note, replayed_to"
    }
}
