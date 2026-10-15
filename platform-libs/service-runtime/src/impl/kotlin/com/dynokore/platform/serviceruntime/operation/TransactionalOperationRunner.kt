package com.dynokore.platform.serviceruntime.operation

import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/**
 * [OperationRunner] over the service's [TransactionRunner] and the DSLContext of tenancy-jooq's factory and [AuditWriter] (INV-REQ-957, INV-REQ-1010). The
 * caller has bound the tenant (ARC-21). Order: the role and scope check (b2, CS-23); then in one transaction
 * (ARC-05) a transaction-scoped lock on (operation id, repeat key), so two concurrent commands with the same key
 * run one after the other and the second sees the first's committed state (INV-REQ-1010); the repeat check
 * against state (b7: a repeat returns the earlier result and records nothing), the
 * preconditions against state (b3: an unmet one refuses and changes nothing), the effect (b4) and, for an
 * audited operation, its audit entry in the same transaction (b5, INV-REQ-1226 b4), so an audit write that
 * fails rolls the effect back.
 */
class TransactionalOperationRunner(
    private val transactions: TransactionRunner,
    private val dsl: DSLContext,
    private val audit: AuditWriter,
) : OperationRunner {
    override fun <C, S, R> run(
        operation: DeclaredOperation<C, S, R>,
        actor: String,
        command: C,
    ): OperationOutcome<R> {
        if (!operation.permits(actor, command)) return OperationOutcome.NotPermitted
        val key = operation.repeatKey(command)
        check(key.isNotBlank()) { "${operation.operationId} declares an empty repeat key" }
        return transactions.inTransaction {
            lockRepeatKey(operation.operationId, key)
            val state = operation.load(command)
            val prior = operation.priorResult(command, state)
            val unmet = if (prior == null) operation.unmetPrecondition(command, state) else null
            when {
                prior != null -> {
                    OperationOutcome.Repeated(prior)
                }

                unmet != null -> {
                    OperationOutcome.PreconditionUnmet(unmet)
                }

                else -> {
                    val result = operation.perform(command, state)
                    val entry = operation.audit(command, state, result)
                    if (operation.audited) {
                        checkNotNull(entry) { "${operation.operationId} is audited but recorded no audit entry" }
                        audit.record(
                            AuditEntry(actor, operation.operationId, entry.scope, entry.reason, entry.priorStateJson),
                            entry.context,
                        )
                    } else {
                        check(entry == null) { "${operation.operationId} is not audited but returned an audit entry" }
                    }
                    OperationOutcome.Performed(result)
                }
            }
        }
    }

    /**
     * Serialises same-key commands: a PostgreSQL advisory lock held until the transaction ends, so it is released
     * on commit and on rollback. A hash collision between two different keys only serialises them too.
     */
    private fun lockRepeatKey(
        operationId: String,
        key: String,
    ) {
        val lock =
            DSL.function(
                DSL.name("pg_advisory_xact_lock"),
                SQLDataType.OTHER,
                DSL.function(
                    DSL.name("hashtextextended"),
                    SQLDataType.BIGINT,
                    DSL.`val`("$operationId:$key"),
                    DSL.`val`(0L),
                ),
            )
        dsl.select(lock).fetch()
    }
}
