package com.dynokore.platform.serviceruntime.operation

import com.dynokore.platform.outboxandqueue.EventContext

/**
 * How an operation is corrected (INV-REQ-957 b6, INV-NOTE-3 §31.3): undone, compensated by another operation, or
 * not at all. It sets how the operation is confirmed and corrected; the contract states it as
 * `x-operation.reversibility`.
 */
enum class Reversibility { UNDOABLE, COMPENSABLE, IRREVERSIBLE }

/**
 * What an audited operation records (INV-REQ-957 b5), written in the operation's transaction (INV-REQ-1226 b4).
 * The actor and the action (the operation id) come from the runner, never from the operation.
 */
data class OperationAudit(
    val scope: String,
    val reason: String?,
    val priorStateJson: String?,
    val context: EventContext,
)

/**
 * One operation as INV-REQ-957 declares it: any action that changes persistent state or transmits to a third
 * party, with its six properties. [C] is the command, [S] the state of the records it changes, [R] its result.
 * An [OperationRunner] enforces the declaration; the operation itself only answers.
 */
interface DeclaredOperation<C, S, R> {
    /** The contract's `operationId` (API-02). */
    val operationId: String

    /** b6: declared, never defaulted. */
    val reversibility: Reversibility

    /** b5: whether an immutable audit entry records it. */
    val audited: Boolean

    /** b2: true when [actor] holds the operation's role within the command's scope (INV-REQ-859). */
    fun permits(
        actor: String,
        command: C,
    ): Boolean

    /** b7, INV-REQ-1010: the key that makes a repeat safe, declared per operation. */
    fun repeatKey(command: C): String

    /** b3: reads the state of the records the command changes, in the operation's transaction. */
    fun load(command: C): S

    /**
     * b7: the result of an earlier performance with the same [repeatKey], read from [state], or `null` when the
     * command has not been performed. A repeat returns it with no second effect (INV-REQ-1010: the original
     * result, a no-op or "no change", per operation).
     */
    fun priorResult(
        command: C,
        state: S,
    ): R?

    /** b3: `null` when the preconditions hold against [state], else the catalogue code that refuses the command. */
    fun unmetPrecondition(
        command: C,
        state: S,
    ): String?

    /** b4: the effect, the records it changes and the events it emits, in the operation's transaction. */
    fun perform(
        command: C,
        state: S,
    ): R

    /** b5: the audit entry of a performance; required when [audited]. */
    fun audit(
        command: C,
        state: S,
        result: R,
    ): OperationAudit?
}

/** What happened to a command. Nothing changed unless [Performed]. */
sealed interface OperationOutcome<out R> {
    data class Performed<R>(val result: R) : OperationOutcome<R>

    /** A repeat with the same key: the earlier result, no second effect and no second audit entry (b7). */
    data class Repeated<R>(val result: R) : OperationOutcome<R>

    /** The actor does not hold the role within the scope (b2). */
    data object NotPermitted : OperationOutcome<Nothing>

    /** A precondition is unmet against state (b3); [code] is the catalogue code. */
    data class PreconditionUnmet(val code: String) : OperationOutcome<Nothing>
}

/**
 * Enforces a [DeclaredOperation] (INV-REQ-957, INV-REQ-1010): the role and scope check, then in one transaction
 * the repeat check, the precondition against state, the effect and, where audited, the audit entry.
 */
interface OperationRunner {
    fun <C, S, R> run(
        operation: DeclaredOperation<C, S, R>,
        actor: String,
        command: C,
    ): OperationOutcome<R>
}

/**
 * The declared operations of a service, by operation id: one declaration per id, each with its reversibility,
 * so the set can be checked against the contract's `x-operation` (INV-REQ-957 b1, b6).
 */
class OperationRegistry {
    private val declared = LinkedHashMap<String, DeclaredOperation<*, *, *>>()

    @Synchronized
    fun declare(operation: DeclaredOperation<*, *, *>) {
        require(operation.operationId.isNotBlank()) { "an operation has an operation id" }
        require(declared.putIfAbsent(operation.operationId, operation) == null) {
            "operation ${operation.operationId} is already declared"
        }
    }

    /** The reversibility of each declared operation, by operation id. */
    @Synchronized
    fun reversibilities(): Map<String, Reversibility> = declared.mapValues { it.value.reversibility }

    @Synchronized
    fun get(operationId: String): DeclaredOperation<*, *, *>? = declared[operationId]
}
