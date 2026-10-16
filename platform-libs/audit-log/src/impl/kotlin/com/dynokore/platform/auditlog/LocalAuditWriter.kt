package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.tenancykernel.SqlIdentifier
import org.jooq.DSLContext
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * [AuditWriter] for a service without an outbox (`control-plane-svc`, INV-Q-2958): one row in the
 * [AuditSchema.AUDIT_ENTRY] table of the service's own schema (`controlplane`), through the module's jOOQ [DSLContext] (tenancy-jooq, on the
 * caller's open transaction), so the entry commits with the change it records or vanishes with it
 * (INV-REQ-1226, INV-REQ-2588, ARC-05). It never opens, commits or rolls back a transaction.
 *
 * The row carries the context's mandatory tenant under forced row-level security (CS-19): the session's
 * tenant, or, for an operator action with no tenant, tenancy-kernel's reserved `TenantId.PLATFORM` bound by
 * `PlatformTenantBinding` with `EntityRef.PLATFORM` (INV-Q-2927 option D). The returned [Envelope] is the one
 * the outbox writer would have stamped (type `AuditEntryRecorded`, no `docsequence`); nothing is published.
 * Any failure of the insert surfaces as [AuditWriteFailed].
 *
 * It is also the [AuditStager] of such a service (coordinator ruling INV-Q-4277): [stage] builds the same row and
 * writes nothing, for a SECURITY DEFINER function that writes its own entry (`purge_audit_entries`,
 * [AuditSchema.purgeDdl]); [record] is [stage] followed by the insert.
 */
class LocalAuditWriter(
    private val dsl: DSLContext,
    schema: String,
    private val service: String,
    private val instanceId: String,
    private val clock: Clock,
    private val mapper: ObjectMapper = ObjectMapper(),
) : AuditWriter,
    AuditStager {
    // INV-REQ-854 b5 (coordinator ruling INV-Q-4036): the schema enters SQL text only through SqlIdentifier.require.
    private val schemaName = SqlIdentifier.require(schema, "schema")
    private val ids = UuidV7()
    private val table: Table<*> = DSL.table(DSL.name(schemaName, AuditSchema.AUDIT_ENTRY))

    init {
        require(SERVICE.matches(service)) {
            "service must be a lower-case name like 'control-plane-svc', was '$service'"
        }
    }

    override fun record(
        entry: AuditEntry,
        context: EventContext,
    ): Envelope {
        val staged = stage(entry, context)
        try {
            insert(staged)
        } catch (failure: Exception) {
            // includes the checked SQLException of a refused insert
            throw AuditWriteFailed(failure)
        }
        return staged.envelope
    }

    override fun stage(
        entry: AuditEntry,
        context: EventContext,
    ): StagedAuditEntry {
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
        return StagedAuditEntry(envelope, entry, priorState)
    }

    private fun insert(staged: StagedAuditEntry) {
        val envelope = staged.envelope
        val entry = staged.entry
        dsl
            .insertInto(table)
            .columns(
                ID,
                TENANT_ID,
                ENTITY_REF,
                RECORDED_AT,
                SOURCE,
                SUBJECT,
                DOC_UUID,
                TRANSACTION_DATE,
                INSTANCE_ID,
                CORRELATION_ID,
                CAUSATION_ID,
                ACTOR,
                ACTION,
                SCOPE,
                REASON,
                PRIOR_STATE,
            ).values(
                UUID.fromString(envelope.id.value),
                envelope.tenant.value,
                envelope.entity.value,
                envelope.time.atOffset(ZoneOffset.UTC),
                envelope.source,
                envelope.subject,
                envelope.docUuid?.value,
                envelope.transactionDate,
                envelope.instanceId,
                envelope.correlation.value,
                envelope.causation.value,
                entry.actor,
                entry.action,
                entry.scope,
                entry.reason,
                DSL.`val`(staged.priorStateJson, PRIOR_STATE).value,
            ).execute()
    }

    private companion object {
        val SERVICE = Regex("^[a-z][a-z-]*$")

        val ID = DSL.field(DSL.name("id"), SQLDataType.UUID)
        val TENANT_ID = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
        val ENTITY_REF = DSL.field(DSL.name("entity_ref"), SQLDataType.CLOB)
        val RECORDED_AT = DSL.field(DSL.name("recorded_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
        val SOURCE = DSL.field(DSL.name("source"), SQLDataType.CLOB)
        val SUBJECT = DSL.field(DSL.name("subject"), SQLDataType.CLOB)
        val DOC_UUID = DSL.field(DSL.name("doc_uuid"), SQLDataType.UUID)
        val TRANSACTION_DATE = DSL.field(DSL.name("transaction_date"), SQLDataType.LOCALDATE)
        val INSTANCE_ID = DSL.field(DSL.name("instance_id"), SQLDataType.CLOB)
        val CORRELATION_ID = DSL.field(DSL.name("correlation_id"), SQLDataType.CLOB)
        val CAUSATION_ID = DSL.field(DSL.name("causation_id"), SQLDataType.CLOB)
        val ACTOR = DSL.field(DSL.name("actor"), SQLDataType.CLOB)
        val ACTION = DSL.field(DSL.name("action"), SQLDataType.CLOB)
        val SCOPE = DSL.field(DSL.name("scope"), SQLDataType.CLOB)
        val REASON = DSL.field(DSL.name("reason"), SQLDataType.CLOB)
        val PRIOR_STATE = DSL.field(DSL.name("prior_state"), SQLDataType.JSONB)
    }
}
