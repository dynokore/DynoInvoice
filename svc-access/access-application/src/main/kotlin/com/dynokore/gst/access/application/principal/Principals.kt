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

/**
 * Writes the PRINCIPAL entry of the revocation list (INV-API-4001, INV-Q-8785 A) in its own transaction bound to the
 * platform tenant: it locks the list head, assigns head + 1 and inserts one entry unless a live entry of the same
 * principal exists. It runs before, and not inside, the tenant transaction that sets the principal REVOKED.
 */
fun interface RevocationListWriter {
    fun recordPrincipalRevoked(principalRef: String)
}

sealed interface CreatePrincipalOutcome {
    data class Created(
        val principal: ServicePrincipal,
        val repeated: Boolean,
        val issued: IssuedCredential? = null,
    ) : CreatePrincipalOutcome

    /** The credential fields do not form a credential (INV-REQ-880, VP-77); nothing is created. */
    data class CredentialInvalid(
        val reason: String,
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
    private val issuer: CredentialIssuer,
    private val audit: GrantAudit,
) {
    fun handle(
        actor: String,
        tenant: TenantRef,
        requestedType: String,
        scope: String,
        callingSystem: String?,
        credential: CredentialRequest = CredentialRequest(null, null, null),
    ): CreatePrincipalOutcome {
        val type = PrincipalType.ofContract(requestedType)
        return when {
            type == null -> {
                CreatePrincipalOutcome.TypeNotIssuable(requestedType)
            }

            type == PrincipalType.FILING_WORKFLOW && callingSystem == null -> {
                CreatePrincipalOutcome.CallingSystemMissing
            }

            else -> {
                issue(actor, tenant, type, scope, callingSystem, credential)
            }
        }
    }

    private fun issue(
        actor: String,
        tenant: TenantRef,
        type: PrincipalType,
        scope: String,
        callingSystem: String?,
        credential: CredentialRequest,
    ): CreatePrincipalOutcome =
        tx.inTransaction {
            principals.findActiveByRepeatKey(type, scope, callingSystem)?.let {
                return@inTransaction CreatePrincipalOutcome.Created(it, repeated = true)
            }
            issuer.refusal(credential)?.let { return@inTransaction CreatePrincipalOutcome.CredentialInvalid(it) }
            val principal =
                ServicePrincipal(principals.nextRef(), tenant, type, scope, callingSystem, PrincipalStatus.ACTIVE, 0L)
            principals.insert(principal)
            val issued = if (credential.isEmpty) null else issuer.issue(principal.ref, credential)
            audit.record(GrantAuditEntry(actor, "createPrincipal", "principal:${principal.ref}", null, null))
            CreatePrincipalOutcome.Created(principal, repeated = false, issued = issued)
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
    private val revocationList: RevocationListWriter,
    private val audit: GrantAudit,
) {
    fun handle(
        actor: String,
        ref: String,
    ): RevokePrincipalOutcome {
        val seen = tx.inTransaction { principals.find(ref) }
        return when {
            seen == null -> {
                RevokePrincipalOutcome.NotFound
            }

            seen.status == PrincipalStatus.REVOKED -> {
                RevokePrincipalOutcome.AlreadyRevoked(seen)
            }

            else -> {
                // the list entry first: if the tenant transaction then fails, the gateway already refuses the
                // principal and a repeat call finishes the revocation (the entry write is idempotent in the window)
                revocationList.recordPrincipalRevoked(ref)
                revoke(actor, ref)
            }
        }
    }

    private fun revoke(
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
