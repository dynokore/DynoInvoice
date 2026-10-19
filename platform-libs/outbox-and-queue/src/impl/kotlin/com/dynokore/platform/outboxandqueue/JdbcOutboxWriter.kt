package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

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
    private val dsl: DSLContext,
    schema: String,
    private val service: String,
    private val instanceId: String,
    catalogue: EventCatalogue,
    private val clock: Clock,
) : OutboxWriter {
    private val guard = EmissionGuard(catalogue, service)
    private val ids = UuidV7()
    private val schemaName = SqlIdentifier.require(schema, "schema")
    private val outbox: Table<*> = DSL.table(DSL.name(schemaName, OutboxSchema.OUTBOX))
    private val counter: Table<*> = DSL.table(DSL.name(schemaName, OutboxSchema.OUTBOX_SEQUENCE))
    private val lastValue =
        DSL.field(
            DSL.name(schemaName, OutboxSchema.OUTBOX_SEQUENCE, "last_value"),
            SQLDataType.BIGINT,
        )

    init {
        require(SERVICE.matches(service)) { "service must be a lower-case name like 'submission-svc', was '$service'" }
    }

    override fun append(
        events: List<OutboundEvent>,
        context: EventContext,
    ): List<Envelope> {
        if (events.isEmpty()) return emptyList()
        val entries = permitted(events, context)
        val stamped = context.docUuid != null || context.aggregateVersion != null
        val first = if (stamped) reserveSequence(context, events.size) else null
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return events.mapIndexed { index, event ->
            val envelope = envelope(event, context, now, first?.plus(index))
            insert(entries[index].topic, envelope)
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
        return events.mapIndexed {
            index,
            event,
            ->
            StagedOutboxRow(entries[index].topic, envelope(event, context, now, null))
        }
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
                throw EmissionNotPermitted(
                    service,
                    it.type,
                    "a domain event cannot be written under the platform tenant",
                )
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
            insert(topic, envelope)
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
        replayId: UUID,
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
        context: EventContext,
        count: Int,
    ): Long {
        val wanted = (context.aggregateVersion ?: 1L) + count - 1
        val last =
            dsl
                .insertInto(counter)
                .columns(TENANT_ID, AGGREGATE_KEY, LAST_VALUE)
                .values(context.tenant.value, context.aggregateKey, wanted)
                .onConflict(TENANT_ID, AGGREGATE_KEY)
                .doUpdate()
                .set(LAST_VALUE, DSL.greatest(lastValue.plus(count), DSL.excluded(LAST_VALUE)))
                .returning(LAST_VALUE)
                .fetchOne()
        checkNotNull(last) { "sequence counter returned no row" }
        return checkNotNull(last.get(LAST_VALUE)) - count + 1
    }

    private fun insert(
        topic: String,
        envelope: Envelope,
    ) {
        dsl
            .insertInto(outbox)
            .set(
                mapOf<Field<*>, Any?>(
                    ID to envelope.id.value,
                    TOPIC to topic,
                    TYPE to envelope.type,
                    SOURCE to envelope.source,
                    SUBJECT to envelope.subject,
                    EVENT_TIME to envelope.time.atOffset(ZoneOffset.UTC),
                    TENANT_ID to envelope.tenant.value,
                    ENTITY_REF to envelope.entity.value,
                    DOC_UUID to envelope.docUuid?.value,
                    TRANSACTION_DATE to envelope.transactionDate,
                    DOCSEQUENCE to envelope.docSequence,
                    INSTANCE_ID to envelope.instanceId,
                    CORRELATION_ID to envelope.correlation.value,
                    CAUSATION_ID to envelope.causation.value,
                    DATA to DSL.`val`(envelope.dataJson, SQLDataType.JSONB),
                ),
            ).execute()
    }

    private companion object {
        val SERVICE = Regex("^[a-z][a-z-]*$")

        val ID = DSL.field(DSL.name("id"), SQLDataType.CLOB)
        val TOPIC = DSL.field(DSL.name("topic"), SQLDataType.CLOB)
        val TYPE = DSL.field(DSL.name("type"), SQLDataType.CLOB)
        val SOURCE = DSL.field(DSL.name("source"), SQLDataType.CLOB)
        val SUBJECT = DSL.field(DSL.name("subject"), SQLDataType.CLOB)
        val EVENT_TIME = DSL.field(DSL.name("event_time"), SQLDataType.TIMESTAMPWITHTIMEZONE)
        val TENANT_ID = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
        val ENTITY_REF = DSL.field(DSL.name("entity_ref"), SQLDataType.CLOB)
        val DOC_UUID = DSL.field(DSL.name("doc_uuid"), SQLDataType.UUID)
        val TRANSACTION_DATE = DSL.field(DSL.name("transaction_date"), SQLDataType.LOCALDATE)
        val DOCSEQUENCE = DSL.field(DSL.name("docsequence"), SQLDataType.BIGINT)
        val INSTANCE_ID = DSL.field(DSL.name("instance_id"), SQLDataType.CLOB)
        val CORRELATION_ID = DSL.field(DSL.name("correlation_id"), SQLDataType.CLOB)
        val CAUSATION_ID = DSL.field(DSL.name("causation_id"), SQLDataType.CLOB)
        val DATA = DSL.field(DSL.name("data"), SQLDataType.JSONB)
        val AGGREGATE_KEY = DSL.field(DSL.name("aggregate_key"), SQLDataType.CLOB)
        val LAST_VALUE = DSL.field(DSL.name("last_value"), SQLDataType.BIGINT)
    }
}
