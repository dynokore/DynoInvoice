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
 * The override path over jOOQ (the module's tenancy-jooq [DSLContext]) (INV-REQ-1179, -1180, -1226). Refusals are
 * decided from the command and the
 * catalogue before the transaction opens, so a refused override touches nothing (INV-AC-2166). The grant
 * row and its `AuditEntryRecorded` are written in one transaction: a rollback leaves neither (INV-AC-2209).
 * Also the [OverrideRegister]: a grant answers for exactly the version it was given for.
 * And the [OverrideListing]: the register of an entity over a date range, the read audited in its own transaction.
 */
@Suppress("LongParameterList") // constructor injection under CS-05; INV-Q-4629 (CS-50)
class JdbcOverrideService(
    private val dsl: DSLContext,
    private val tx: TransactionRunner,
    private val audit: AuditWriter,
    private val errors: ErrorCatalogue,
    schema: String,
    private val clock: Clock,
    private val policy: OverridePolicy = OverridePolicy(),
) : OverrideService,
    OverrideRegister,
    OverrideListing {
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

    override fun list(query: OverrideListQuery): OverrideListingResult {
        val entry =
            AuditEntry(
                actor = query.reader,
                action = LIST_ACTION,
                scope = "entity:${query.entity.value}/from:${query.from}/to:${query.to}",
            )
        val context = listContext(query)
        return tx.inTransaction {
            val rows = listRows(query)
            audit.record(entry, context)
            val (undated, dated) = rows.partition { it.transactionDate == null }
            OverrideListingResult(dated, undated)
        }
    }

    private fun listContext(query: OverrideListQuery) =
        EventContext(
            tenant = query.tenant,
            entity = query.entity,
            docUuid = null,
            transactionDate = null,
            aggregateKey = "entity:${query.entity.value}",
            aggregateVersion = null,
            correlation = query.correlation,
            causation = query.causation,
        )

    private fun listRows(query: OverrideListQuery): List<OverrideEntry> =
        dsl
            .select(
                ID,
                DOC_UUID,
                DOCUMENT_VERSION,
                ERROR_CODE,
                ACTOR,
                REASON,
                GRANTED_AT,
                AUDIT_EVENT_ID,
                TRANSACTION_DATE,
            ).from(table)
            .where(
                TENANT_ID.eq(query.tenant.value),
                ENTITY_REF.eq(query.entity.value),
                TRANSACTION_DATE.isNull.or(TRANSACTION_DATE.between(query.from, query.to)),
            ).orderBy(TRANSACTION_DATE.asc().nullsLast(), GRANTED_AT.asc(), ID.asc())
            .fetch()
            .map {
                OverrideEntry(
                    OverrideGrant(
                        DocUuid(checkNotNull(it.get(DOC_UUID))),
                        checkNotNull(it.get(DOCUMENT_VERSION)),
                        checkNotNull(it.get(ERROR_CODE)),
                        checkNotNull(it.get(ACTOR)),
                        checkNotNull(it.get(REASON)),
                        checkNotNull(it.get(GRANTED_AT)).toInstant(),
                        checkNotNull(it.get(AUDIT_EVENT_ID)),
                    ),
                    it.get(TRANSACTION_DATE),
                )
            }

    private fun refuseIfNotAllowed(command: OverrideCommand) {
        refusalOf(command)?.let { throw OverrideRefused(it, command.code) }
    }

    private fun refusalOf(command: OverrideCommand): RefusalReason? =
        when (errors.dispositionOf(command.code)) {
            null -> {
                RefusalReason.UNKNOWN_CODE
            }

            ErrorDisposition.TERMINAL -> {
                RefusalReason.TERMINAL
            }

            ErrorDisposition.MANUAL -> {
                RefusalReason.NOT_OVERRIDABLE
            }

            ErrorDisposition.OVERRIDABLE -> {
                when {
                    command.reason.isNullOrBlank() -> RefusalReason.REASON_REQUIRED
                    !policy.permits(command.actorRoles) -> RefusalReason.ROLE_NOT_AUTHORISED
                    else -> null
                }
            }
        }

    private fun insert(
        command: OverrideCommand,
        grant: OverrideGrant,
    ): Boolean =
        dsl
            .insertInto(table)
            .columns(
                ID,
                TENANT_ID,
                DOC_UUID,
                DOCUMENT_VERSION,
                ERROR_CODE,
                ACTOR,
                REASON,
                GRANTED_AT,
                AUDIT_EVENT_ID,
                ENTITY_REF,
                TRANSACTION_DATE,
            ).values(
                UUID.randomUUID(),
                command.tenant.value,
                grant.docUuid.value,
                grant.documentVersion,
                grant.code,
                grant.actor,
                grant.reason,
                grant.grantedAt.atOffset(ZoneOffset.UTC),
                grant.auditEventId,
                command.entity.value,
                command.transactionDate,
            ).onConflict(TENANT_ID, DOC_UUID, DOCUMENT_VERSION, ERROR_CODE)
            .doNothing()
            .execute() == 1

    private companion object {
        const val ACTION = "override"
        const val LIST_ACTION = "listOverrides"

        val ID = DSL.field(DSL.name("id"), SQLDataType.UUID)
        val TENANT_ID = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
        val DOC_UUID = DSL.field(DSL.name("doc_uuid"), SQLDataType.UUID)
        val DOCUMENT_VERSION = DSL.field(DSL.name("document_version"), SQLDataType.BIGINT)
        val ERROR_CODE = DSL.field(DSL.name("error_code"), SQLDataType.CLOB)
        val ACTOR = DSL.field(DSL.name("actor"), SQLDataType.CLOB)
        val REASON = DSL.field(DSL.name("reason"), SQLDataType.CLOB)
        val GRANTED_AT = DSL.field(DSL.name("granted_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
        val AUDIT_EVENT_ID = DSL.field(DSL.name("audit_event_id"), SQLDataType.CLOB)
        val ENTITY_REF = DSL.field(DSL.name("entity_ref"), SQLDataType.CLOB)
        val TRANSACTION_DATE = DSL.field(DSL.name("transaction_date"), SQLDataType.LOCALDATE)

        fun scopeOf(command: OverrideCommand) =
            "document:${command.docUuid}/version:${command.documentVersion}/code:${command.code}"
    }
}
