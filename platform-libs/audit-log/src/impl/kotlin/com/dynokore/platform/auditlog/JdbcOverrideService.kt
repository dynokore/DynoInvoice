package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.sql.Timestamp
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The override path over JDBC (INV-REQ-1179, -1180, -1226). Refusals are decided from the command and the
 * catalogue before the transaction opens, so a refused override touches nothing (INV-AC-2166). The grant
 * row and its `AuditEntryRecorded` are written in one transaction: a rollback leaves neither (INV-AC-2209).
 * Also the [OverrideRegister]: a grant answers for exactly the version it was given for.
 */
class JdbcOverrideService(
    private val connections: CurrentConnection,
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
    ): Boolean = connections
        .require()
        .prepareStatement(
            "select 1 from $schemaName.${AuditSchema.OVERRIDE_GRANT} where doc_uuid = ? and document_version = ? and error_code = ?",
        ).use { statement ->
            statement.setObject(1, docUuid.value)
            statement.setLong(2, documentVersion)
            statement.setString(3, code)
            statement.executeQuery().use { it.next() }
        }

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
    ): Boolean = connections
        .require()
        .prepareStatement(
            """
            insert into $schemaName.${AuditSchema.OVERRIDE_GRANT} (id, tenant_id, doc_uuid, document_version, error_code, actor, reason, granted_at, audit_event_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (tenant_id, doc_uuid, document_version, error_code) do nothing
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, command.tenant.value)
            statement.setObject(3, grant.docUuid.value)
            statement.setLong(4, grant.documentVersion)
            statement.setString(5, grant.code)
            statement.setString(6, grant.actor)
            statement.setString(7, grant.reason)
            statement.setTimestamp(8, Timestamp.from(grant.grantedAt))
            statement.setString(9, grant.auditEventId)
            statement.executeUpdate() == 1
        }

    private companion object {
        const val ACTION = "override"

        fun scopeOf(command: OverrideCommand) = "document:${command.docUuid}/version:${command.documentVersion}/code:${command.code}"
    }
}
