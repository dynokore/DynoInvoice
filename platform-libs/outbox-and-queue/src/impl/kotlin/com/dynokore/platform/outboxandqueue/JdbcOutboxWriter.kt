package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.CurrentConnection
import java.sql.Connection
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * [OutboxWriter] over JDBC, writing into the `outbox` table of the service's own schema
 * (INV-REQ-1211, [OutboxSchema]) on the connection of the caller's open transaction: it never
 * opens, commits or rolls back one, so the rows commit or vanish with the state change (ARC-05,
 * INV-AC-2184).
 *
 * Stamping (S2-01): the id (UUIDv7) and `docsequence` are assigned here, in the command's
 * transaction. `docsequence` comes from a per-(tenant, aggregate key) counter row that this
 * transaction locks until it commits, so concurrent commands on one aggregate serialise and
 * their outbox rows are numbered, and committed, in sequence order. Both tables carry the mandatory
 * `tenant_id` under tenancy-kernel's row-level security, so a row can only be written for the tenant
 * bound to the transaction (ARC-21). The relay therefore
 * preserves order per key by reading in outbox order (INV-REQ-1221).
 */
class JdbcOutboxWriter(
    private val connections: CurrentConnection,
    schema: String,
    private val service: String,
    private val instanceId: String,
    catalogue: EventCatalogue,
    private val clock: Clock,
) : OutboxWriter {
    private val guard = EmissionGuard(catalogue, service)
    private val ids = UuidV7()
    private val outbox = "${SqlIdentifier.require(schema, "schema")}.${OutboxSchema.OUTBOX}"
    private val counter = "$schema.${OutboxSchema.OUTBOX_SEQUENCE}"

    init {
        require(SERVICE.matches(service)) { "service must be a lower-case name like 'submission-svc', was '$service'" }
    }

    override fun append(
        events: List<OutboundEvent>,
        context: EventContext,
    ): List<Envelope> {
        if (events.isEmpty()) return emptyList()
        // INV-AC-1869: refuse before anything is written, so one bad type leaves no partial batch.
        val entries = events.map { guard.require(it.type) }
        val connection = connections.require()
        val stamped = context.docUuid != null || context.aggregateVersion != null
        val first = if (stamped) reserveSequence(connection, context, events.size) else null
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return events.mapIndexed { index, event ->
            val envelope = envelope(event, context, now, first?.plus(index))
            insert(connection, entries[index].topic, envelope)
            envelope
        }
    }

    private fun envelope(
        event: OutboundEvent,
        context: EventContext,
        now: Instant,
        docSequence: Long?,
    ) = Envelope(
        id = EventId(ids.next(now).toString()),
        source = "/gst/$service",
        type = event.type,
        subject = context.aggregateKey,
        time = now,
        tenant = context.tenant,
        entity = context.entity,
        docUuid = context.docUuid,
        transactionDate = context.transactionDate,
        docSequence = docSequence,
        instanceId = instanceId,
        correlation = context.correlation,
        causation = context.causation,
        dataJson = event.dataJson,
    )

    /** Locks the key's counter row and returns the first of [count] consecutive sequence numbers. */
    private fun reserveSequence(
        connection: Connection,
        context: EventContext,
        count: Int,
    ): Long {
        val wanted = (context.aggregateVersion ?: 1L) + count - 1
        connection
            .prepareStatement(
                """
                insert into $counter (tenant_id, aggregate_key, last_value) values (?, ?, ?)
                on conflict (tenant_id, aggregate_key)
                do update set last_value = greatest($counter.last_value + ?, excluded.last_value)
                returning last_value
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, context.tenant.value)
                statement.setString(2, context.aggregateKey)
                statement.setLong(3, wanted)
                statement.setInt(4, count)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sequence counter returned no row" }
                    return rows.getLong(1) - count + 1
                }
            }
    }

    private fun insert(
        connection: Connection,
        topic: String,
        envelope: Envelope,
    ) {
        connection
            .prepareStatement(
                """
                insert into $outbox
                    (id, topic, type, source, subject, event_time, tenant_id, entity_ref, doc_uuid,
                     transaction_date, docsequence, instance_id, correlation_id, causation_id, data)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """.trimIndent(),
            ).use { statement ->
                var column = 0
                fun next() = ++column
                statement.setString(next(), envelope.id.value)
                statement.setString(next(), topic)
                statement.setString(next(), envelope.type)
                statement.setString(next(), envelope.source)
                statement.setString(next(), envelope.subject)
                statement.setTimestamp(next(), Timestamp.from(envelope.time))
                statement.setObject(next(), envelope.tenant.value)
                statement.setString(next(), envelope.entity.value)
                statement.setObject(next(), envelope.docUuid?.value, java.sql.Types.OTHER)
                statement.setObject(next(), envelope.transactionDate, java.sql.Types.DATE)
                statement.setObject(next(), envelope.docSequence, java.sql.Types.BIGINT)
                statement.setString(next(), envelope.instanceId)
                statement.setString(next(), envelope.correlation.value)
                statement.setString(next(), envelope.causation.value)
                statement.setString(next(), envelope.dataJson)
                statement.executeUpdate()
            }
    }

    private companion object {
        val SERVICE = Regex("^[a-z][a-z-]*$")
    }
}
