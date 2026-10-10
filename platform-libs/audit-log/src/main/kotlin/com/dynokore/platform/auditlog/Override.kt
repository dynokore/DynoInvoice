package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.time.LocalDate

/**
 * An override of one error code on one document version (INV-REQ-1180).
 *
 * @property actorRoles the roles the verified principal holds for this scope; the command API takes
 *   them from the token, never from the request body.
 * @property priorStateJson the document's state before the override, as a JSON object, recorded in the entry.
 */
data class OverrideCommand(
    val tenant: TenantId,
    val entity: EntityRef,
    val docUuid: DocUuid,
    val documentVersion: Long,
    val transactionDate: LocalDate?,
    val code: String,
    val reason: String?,
    val actor: String,
    val actorRoles: Set<String>,
    val priorStateJson: String,
    val correlation: CorrelationId,
    val causation: CausationId,
) {
    init {
        require(documentVersion >= 1) { "document version starts at 1" }
        require(code.isNotBlank()) { "error code must not be blank" }
        require(actor.isNotBlank()) { "actor must not be blank" }
    }
}

/** A recorded override: valid for exactly [documentVersion] of [docUuid], never for the next one. */
data class OverrideGrant(
    val docUuid: DocUuid,
    val documentVersion: Long,
    val code: String,
    val actor: String,
    val reason: String,
    val grantedAt: Instant,
    val auditEventId: String,
)

/** Why an override was refused. Nothing is written for any of them (INV-AC-2166). */
enum class RefusalReason {
    /** INV-REQ-1179: no role can override it. */
    TERMINAL,

    /** The code is `MANUAL`: it is corrected at source (INV-REQ-1186). */
    NOT_OVERRIDABLE,

    /** The catalogue does not know the code: fail closed. */
    UNKNOWN_CODE,

    /** An override needs a reason (INV-REQ-1180). */
    REASON_REQUIRED,

    /** The actor holds no role authorised to override. */
    ROLE_NOT_AUTHORISED,

    /** This code is already overridden for this document version. */
    ALREADY_OVERRIDDEN,
}

/** The owning service refuses the override command. */
class OverrideRefused(
    val reason: RefusalReason,
    val code: String,
) : RuntimeException("override of $code refused: $reason")

/**
 * The override path, embedded in the owning service's command API (INV-REQ-1179, -1180). The tenant is
 * bound by the adapter (ARC-21).
 */
interface OverrideService {
    /**
     * Records the override and its `AuditEntryRecorded` in one transaction, or neither.
     *
     * @throws OverrideRefused for a TERMINAL or MANUAL or unknown code, a missing reason, or an
     *   unauthorised role; checked before any write, so a refusal leaves nothing (INV-AC-2166).
     */
    fun override(command: OverrideCommand): OverrideGrant
}

/**
 * What the owning service asks when it raises a check on a document version: is this code overridden
 * for this very version? A new version raises the check afresh (INV-REQ-1180, INV-AC-2167).
 * Call it inside the service's transaction.
 */
interface OverrideRegister {
    fun isOverridden(
        docUuid: DocUuid,
        documentVersion: Long,
        code: String,
    ): Boolean
}

/** Who may override. The default is the Keel role `tax.approver` (INV-REQ-1293, INV-AC-2279). */
data class OverridePolicy(
    val authorisedRoles: Set<String> = setOf(TAX_APPROVER),
) {
    init {
        require(authorisedRoles.isNotEmpty()) { "at least one role must be authorised to override" }
    }

    companion object {
        const val TAX_APPROVER = "tax.approver"
    }
}
