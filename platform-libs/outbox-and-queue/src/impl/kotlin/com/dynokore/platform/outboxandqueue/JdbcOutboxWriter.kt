package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
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
 * bound to the transaction (ARC-21). A tenantless platform event is stored under tenancy-kernel's reserved
 * `TenantId.PLATFORM` and [EntityRef.PLATFORM] (INV-Q-2927 option D), so `tenant_id` stays NOT NULL with
 * forced RLS (CS-19); only platform types may be written so (INV-Q-3018). The relay therefore
 * preserves order per key by reading in outbox order (INV-REQ-1221).
 *
 * [stage] builds the row [append] would write for an unsequenced platform event and writes nothing, for a SECURITY
 * DEFINER function that inserts it with the change it records (coordinator ruling INV-Q-4254 A).
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
    private val counter = "${SqlIdentifier.require(schema, "schema")}.${OutboxSchema.OUTBOX_SEQUENCE}"

    init {
        require(SERVICE.matches(service)) { "service must be a lower-case name like 'submission-svc', was '$service'" }
    }

    override fun append(
        events: List<OutboundEvent>,
        context: EventContext,
    ): List<Envelope> {
        if (events.isEmpty()) return emptyList()
        val entries = permitted(events, context)
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

    /**
     * The rows [append] would write for unsequenced platform events, built and not written (coordinator ruling
     * INV-Q-4254 A): the same emission checks, the same envelope, the catalogued topic. It never touches the
     * connection, so it reserves no `docsequence`.
     */
    override fun stage(
        events: List<OutboundEvent>,
        context: EventContext,
    ): List<StagedOutboxRow> {
        require(context.docUuid == null && context.aggregateVersion == null) {
            "only unsequenced events are staged: no Doc UUID and no aggregate version"
        }
        events.firstOrNull { !Envelope.isPlatformType(it.type) }?.let {
            throw IllegalArgumentException("only platform events are staged, was '${it.type}'")
        }
        if (events.isEmpty()) return emptyList()
        val entries = permitted(events, context)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return events.mapIndexed { index, event -> StagedOutboxRow(entries[index].topic, envelope(event, context, now, null)) }
    }

    /** The catalogue entry of every event, checked for the whole batch before anything is built or written. */
    private fun permitted(
        events: List<OutboundEvent>,
        context: EventContext,
    ): List<CatalogueEntry> {
        // INV-AC-1869: refuse before anything is written, so one bad type leaves no partial batch.
        val entries = events.map { guard.require(it.type) }
        // INV-Q-3018: under the reserved platform tenant only platform event types are written; a domain
        // event always carries a real tenant (FR-K.3). Checked for the whole batch before any row.
        if (context.tenant.isPlatform) {
            events.firstOrNull { !Envelope.isPlatformType(it.type) }?.let {
                throw EmissionNotPermitted(service, it.type, "a domain event cannot be written under the platform tenant")
            }
        }
        // INV-Q-3165: the tenant-wide entity is for platform event types only (FR-K.3).
        if (context.entity.isTenantWide) {
            events.firstOrNull { !Envelope.isPlatformType(it.type) }?.let {
                throw EmissionNotPermitted(service, it.type, "a domain event cannot carry the tenant-wide entity")
            }
        }
        return entries
    }

    /**
     * Writes a replay's re-emitted [states] to the pair's replay [topic] (INV-REQ-1205 b4, INV-Q-3601) in the caller's
     * open transaction, under [tenant] (the bound tenant): each gets a fresh id and keeps the `docsequence` of the
     * state it carries ([ReplayedState.docSequence]); the producer's own per-key counter is not touched, since the
     * state's sequence was already issued (INV-Q-3603). Refused, with nothing written, when this service may not
     * insert a type into [topic].
     */
    fun appendReplayed(
        topic: String,
        states: List<ReplayedState>,
        tenant: TenantId,
        correlation: CorrelationId,
        causation: CausationId,
    ): List<Envelope> {
        states.forEach { guard.requireReplay(topic, it.type) }
        require(!tenant.isPlatform || states.all { Envelope.isPlatformType(it.type) }) {
            "a domain event cannot be written under the platform tenant"
        }
        val connection = connections.require()
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return states.map { state ->
            val envelope =
                Envelope(
                    id = EventId(ids.next(now).toString()),
                    source = "/gst/$service",
                    type = state.type,
                    subject = state.aggregateKey,
                    time = now,
                    tenant = tenant,
                    entity = state.entity,
                    docUuid = state.docUuid,
                    transactionDate = state.transactionDate,
                    docSequence = state.docSequence,
                    instanceId = instanceId,
                    correlation = correlation,
                    causation = causation,
                    dataJson = state.dataJson,
                )
            insert(connection, topic, envelope)
            envelope
        }
    }

    /**
     * Writes the `ReplayCompleted` marker of [replayId] to the replay [topic] (INV-Q-3644), under the platform tenant
     * the caller's transaction has bound (INV-REQ-1226 b7): written last, in the transaction that commits the request
     * as handled (INV-Q-3676).
     */
    fun appendCompleted(
        topic: String,
        replayId: java.util.UUID,
        eventsReplayed: Long,
        correlation: CorrelationId,
        causation: CausationId,
    ): Envelope {
        val data = """{"replayId":"$replayId","eventsReplayed":$eventsReplayed}"""
        val marker = ReplayedState(ReplayProtocol.REPLAY_COMPLETED, data, EntityRef.PLATFORM, "replay/$replayId", null)
        return appendReplayed(topic, listOf(marker), TenantId.PLATFORM, correlation, causation).single()
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
