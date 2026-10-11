package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.tenancykernel.CurrentConnection
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.sql.Types
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * [AuditWriter] for a service without an outbox (`control-plane-svc`, INV-Q-2958): one row in the
 * [AuditSchema.AUDIT_ENTRY] table of the service's own schema (`controlplane`), on the connection of the
 * caller's open transaction, so the entry commits with the change it records or vanishes with it
 * (INV-REQ-1226, INV-REQ-2588, ARC-05). It never opens, commits or rolls back a transaction.
 *
 * The row carries the context's mandatory tenant under forced row-level security (CS-19): the session's
 * tenant, or, for an operator action with no tenant, tenancy-kernel's reserved `TenantId.PLATFORM` bound by
 * `PlatformTenantBinding` with `EntityRef.PLATFORM` (INV-Q-2927 option D). The returned [Envelope] is the one
 * the outbox writer would have stamped (type `AuditEntryRecorded`, no `docsequence`); nothing is published.
 * Any failure of the insert surfaces as [AuditWriteFailed].
 */
class LocalAuditWriter(
    private val connections: CurrentConnection,
    schema: String,
    private val service: String,
    private val instanceId: String,
    private val clock: Clock,
    private val mapper: ObjectMapper = ObjectMapper(),
) : AuditWriter {
    private val table = "${AuditSchema.identifier(schema, "schema")}.${AuditSchema.AUDIT_ENTRY}"
    private val ids = UuidV7()

    init {
        require(SERVICE.matches(service)) {
            "service must be a lower-case name like 'control-plane-svc', was '$service'"
        }
    }

    override fun record(
        entry: AuditEntry,
        context: EventContext,
    ): Envelope {
        val data = AuditPayload.json(entry, mapper)
        val priorState = AuditPayload.priorState(entry, mapper)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val envelope =
            Envelope(
                id = EventId(ids.next(now).toString()),
                source = "/gst/$service",
                type = AuditPayload.EVENT_TYPE,
                subject = context.aggregateKey,
                time = now,
                tenant = context.tenant,
                entity = context.entity,
                docUuid = context.docUuid,
                transactionDate = context.transactionDate,
                docSequence = null,
                instanceId = instanceId,
                correlation = context.correlation,
                causation = context.causation,
                dataJson = data,
            )
        try {
            insert(entry, priorState, envelope)
        } catch (failure: Exception) { // includes the checked SQLException of a refused insert
            throw AuditWriteFailed(failure)
        }
        return envelope
    }

    private fun insert(
        entry: AuditEntry,
        priorState: String?,
        envelope: Envelope,
    ) {
        connections
            .require()
            .prepareStatement(
                """
                insert into $table
                    (id, tenant_id, entity_ref, recorded_at, source, subject, doc_uuid, transaction_date,
                     instance_id, correlation_id, causation_id, actor, action, scope, reason, prior_state)
                values (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """.trimIndent(),
            ).use { statement ->
                var column = 0
                fun next() = ++column
                statement.setString(next(), envelope.id.value)
                statement.setObject(next(), envelope.tenant.value)
                statement.setString(next(), envelope.entity.value)
                statement.setTimestamp(next(), Timestamp.from(envelope.time))
                statement.setString(next(), envelope.source)
                statement.setString(next(), envelope.subject)
                statement.setObject(next(), envelope.docUuid?.value, Types.OTHER)
                statement.setObject(next(), envelope.transactionDate, Types.DATE)
                statement.setString(next(), envelope.instanceId)
                statement.setString(next(), envelope.correlation.value)
                statement.setString(next(), envelope.causation.value)
                statement.setString(next(), entry.actor)
                statement.setString(next(), entry.action)
                statement.setString(next(), entry.scope)
                statement.setString(next(), entry.reason)
                statement.setString(next(), priorState)
                statement.executeUpdate()
            }
    }

    private companion object {
        val SERVICE = Regex("^[a-z][a-z-]*$")
    }
}
