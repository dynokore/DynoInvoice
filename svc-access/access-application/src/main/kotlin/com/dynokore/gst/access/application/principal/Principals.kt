package com.dynokore.gst.access.application.principal

import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.principal.PrincipalStatus
import com.dynokore.gst.access.domain.principal.PrincipalType
import com.dynokore.gst.access.domain.principal.ServicePrincipal
import com.dynokore.platform.tenancykernel.TransactionRunner

/** Persistence of service principals; every read runs under the bound tenant and is live. */
interface PrincipalRepository {
    fun find(ref: String): ServicePrincipal?

    /** The ACTIVE principal with this repeat key (type, scope, callingSystem), if any. */
    fun findActiveByRepeatKey(
        type: PrincipalType,
        scope: String,
        callingSystem: String?,
    ): ServicePrincipal?

    fun insert(principal: ServicePrincipal)

    /** Replaces [before] by [after]; throws the platform's StaleAggregate when [before] is no longer current. */
    fun update(
        before: ServicePrincipal,
        after: ServicePrincipal,
    )

    /** Locks the principal row, then returns it fresh. */
    fun lock(ref: String): ServicePrincipal?

    fun nextRef(): String

    /** The next value of access-svc's one monotonic authority sequence, drawn after the principal row is locked. */
    fun nextAuthorityVersion(): Long
}

/**
 * Publishes AuthorityChanged with changeKind REVOCATION (INV-API-3325) in the command transaction: one event per entity
 * the principal's scope reaches, each at the next value of [nextVersion].
 */
interface PrincipalRevocationPublisher {
    fun publishRevoked(
        principal: ServicePrincipal,
        nextVersion: () -> Long,
    )
}

sealed interface CreatePrincipalOutcome {
    data class Created(
        val principal: ServicePrincipal,
        val repeated: Boolean,
    ) : CreatePrincipalOutcome

    /** The requested type is not one of the six (a legacy member or unknown text). */
    data class TypeNotIssuable(
        val requested: String,
    ) : CreatePrincipalOutcome

    /** A FILING_WORKFLOW request without callingSystem (INV-REQ-587). */
    data object CallingSystemMissing : CreatePrincipalOutcome
}

/** `createPrincipal`: one principal of exactly one of the six types; nothing is created on a refusal. */
class CreatePrincipalUseCase(
    private val tx: TransactionRunner,
    private val principals: PrincipalRepository,
    private val audit: GrantAudit,
) {
    fun handle(
        actor: String,
        tenant: TenantRef,
        requestedType: String,
        scope: String,
        callingSystem: String?,
    ): CreatePrincipalOutcome {
        val type =
            PrincipalType.ofContract(requestedType) ?: return CreatePrincipalOutcome.TypeNotIssuable(requestedType)
        if (type == PrincipalType.FILING_WORKFLOW &&
            callingSystem == null
        ) {
            return CreatePrincipalOutcome.CallingSystemMissing
        }
        return tx.inTransaction {
            principals.findActiveByRepeatKey(type, scope, callingSystem)?.let {
                return@inTransaction CreatePrincipalOutcome.Created(it, repeated = true)
            }
            val principal =
                ServicePrincipal(principals.nextRef(), tenant, type, scope, callingSystem, PrincipalStatus.ACTIVE, 0L)
            principals.insert(principal)
            audit.record(GrantAuditEntry(actor, "createPrincipal", "principal:${principal.ref}", null, null))
            CreatePrincipalOutcome.Created(principal, repeated = false)
        }
    }
}

sealed interface RevokePrincipalOutcome {
    data class Revoked(
        val principal: ServicePrincipal,
    ) : RevokePrincipalOutcome

    /** Repeat key `principalRef + action=REVOKE`: a no-op, no second event. */
    data class AlreadyRevoked(
        val principal: ServicePrincipal,
    ) : RevokePrincipalOutcome

    data object NotFound : RevokePrincipalOutcome
}

/** `revokePrincipal`: the principal is REVOKED at once and AuthorityChanged (REVOCATION) is published. */
class RevokePrincipalUseCase(
    private val tx: TransactionRunner,
    private val principals: PrincipalRepository,
    private val publisher: PrincipalRevocationPublisher,
    private val audit: GrantAudit,
) {
    fun handle(
        actor: String,
        ref: String,
    ): RevokePrincipalOutcome =
        tx.inTransaction {
            val current = principals.lock(ref) ?: return@inTransaction RevokePrincipalOutcome.NotFound
            if (current.status ==
                PrincipalStatus.REVOKED
            ) {
                return@inTransaction RevokePrincipalOutcome.AlreadyRevoked(current)
            }
            val after = current.revoked()
            principals.update(current, after)
            publisher.publishRevoked(after, principals::nextAuthorityVersion)
            audit.record(
                GrantAuditEntry(
                    actor,
                    "revokePrincipal",
                    "principal:${current.ref}",
                    null,
                    """{"status":"${current.status}","type":"${current.type}"}""",
                ),
            )
            RevokePrincipalOutcome.Revoked(after)
        }
}
