package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Clock
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The override path over jOOQ (the module's tenancy-jooq [DSLContext]) (INV-REQ-1179, -1180, -1226). Refusals are decided from the command and the
 * catalogue before the transaction opens, so a refused override touches nothing (INV-AC-2166). The grant
 * row and its `AuditEntryRecorded` are written in one transaction: a rollback leaves neither (INV-AC-2209).
 * Also the [OverrideRegister]: a grant answers for exactly the version it was given for.
 */
class JdbcOverrideService(
    private val dsl: DSLContext,
    private val tx: TransactionRunner,
    private val audit: AuditWriter,
    private val errors: ErrorCatalogue,
    schema: String,
    private val clock: Clock,
    private val policy: OverridePolicy = OverridePolicy(),
) : OverrideService,
    OverrideRegister {
    // INV-REQ-854 b5 (coordinator ruling INV-Q-4036): the schema enters SQL text only through SqlIdentifier.require.
    private val schemaName = SqlIdentifier.require(schema, "schema")
    private val table: Table<*> = DSL.table(DSL.name(schemaName, AuditSchema.OVERRIDE_GRANT))

    override fun override(command: OverrideCommand): OverrideGrant {
        refuseIfNotAllowed(command)
        val reason = checkNotNull(command.reason)
        return tx.inTransaction {
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            val envelope =
                audit.record(
                    AuditEntry(
                        actor = command.actor,
                        action = ACTION,
                        scope = scopeOf(command),
                        reason = reason,
                        priorStateJson = command.priorStateJson,
                    ),
                    EventContext(
                        tenant = command.tenant,
                        entity = command.entity,
                        docUuid = command.docUuid,
                        transactionDate = command.transactionDate,
                        aggregateKey = command.docUuid.toString(),
                        aggregateVersion = null,
                        correlation = command.correlation,
                        causation = command.causation,
                    ),
                )
            val grant =
                OverrideGrant(
                    command.docUuid,
                    command.documentVersion,
                    command.code,
                    command.actor,
                    reason,
                    now,
                    envelope.id.value,
                )
            if (!insert(command, grant)) throw OverrideRefused(RefusalReason.ALREADY_OVERRIDDEN, command.code)
            grant
        }
    }

    override fun isOverridden(
        docUuid: DocUuid,
        documentVersion: Long,
        code: String,
    ): Boolean =
        dsl
            .selectOne()
            .from(table)
            .where(DOC_UUID.eq(docUuid.value), DOCUMENT_VERSION.eq(documentVersion), ERROR_CODE.eq(code))
            .fetchOne() != null

    private fun refuseIfNotAllowed(command: OverrideCommand) {
        when (errors.dispositionOf(command.code)) {
            null -> throw OverrideRefused(RefusalReason.UNKNOWN_CODE, command.code)
            ErrorDisposition.TERMINAL -> throw OverrideRefused(RefusalReason.TERMINAL, command.code)
            ErrorDisposition.MANUAL -> throw OverrideRefused(RefusalReason.NOT_OVERRIDABLE, command.code)
            ErrorDisposition.OVERRIDABLE -> Unit
        }
        if (command.reason.isNullOrBlank()) throw OverrideRefused(RefusalReason.REASON_REQUIRED, command.code)
        if (!policy.permits(command.actorRoles)) {
            throw OverrideRefused(RefusalReason.ROLE_NOT_AUTHORISED, command.code)
        }
    }

    private fun insert(
        command: OverrideCommand,
        grant: OverrideGrant,
    ): Boolean =
        dsl
            .insertInto(table)
            .columns(ID, TENANT_ID, DOC_UUID, DOCUMENT_VERSION, ERROR_CODE, ACTOR, REASON, GRANTED_AT, AUDIT_EVENT_ID)
            .values(
                UUID.randomUUID(),
                command.tenant.value,
                grant.docUuid.value,
                grant.documentVersion,
                grant.code,
                grant.actor,
                grant.reason,
                grant.grantedAt.atOffset(ZoneOffset.UTC),
                grant.auditEventId,
            ).onConflict(TENANT_ID, DOC_UUID, DOCUMENT_VERSION, ERROR_CODE)
            .doNothing()
            .execute() == 1

    private companion object {
        const val ACTION = "override"

        val ID = DSL.field(DSL.name("id"), SQLDataType.UUID)
        val TENANT_ID = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
        val DOC_UUID = DSL.field(DSL.name("doc_uuid"), SQLDataType.UUID)
        val DOCUMENT_VERSION = DSL.field(DSL.name("document_version"), SQLDataType.BIGINT)
        val ERROR_CODE = DSL.field(DSL.name("error_code"), SQLDataType.CLOB)
        val ACTOR = DSL.field(DSL.name("actor"), SQLDataType.CLOB)
        val REASON = DSL.field(DSL.name("reason"), SQLDataType.CLOB)
        val GRANTED_AT = DSL.field(DSL.name("granted_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
        val AUDIT_EVENT_ID = DSL.field(DSL.name("audit_event_id"), SQLDataType.CLOB)

        fun scopeOf(command: OverrideCommand) =
            "document:${command.docUuid}/version:${command.documentVersion}/code:${command.code}"
    }
}
