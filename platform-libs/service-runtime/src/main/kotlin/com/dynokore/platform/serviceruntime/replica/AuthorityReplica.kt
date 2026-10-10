package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Who is acting. Only [MACHINE] keeps working when no decision can be had (INV-REQ-1201 behaviours #4 and #5). */
enum class PrincipalKind { HUMAN, CONSENT_AGENT, MACHINE }

data class Principal(val id: String, val kind: PrincipalKind)

data class AuthorityKey(val tenant: TenantId, val principalId: String)

data class Grant(
    val entityRef: String,
    val action: String,
    val validFrom: Instant,
    val validUntil: Instant? = null,
) {
    fun covers(
        entityRef: String,
        action: String,
        at: Instant,
    ): Boolean = this.entityRef == entityRef && this.action == action &&
        !at.isBefore(validFrom) && (validUntil == null || at.isBefore(validUntil))
}

/** What the replica holds for one principal: replaced whole by each change, so a revocation is just a smaller set. */
data class PrincipalAuthority(val grants: List<Grant>, val revoked: Boolean = false)

/**
 * The platform event `AuthorityChanged` (grant, consent, principal or revocation
 * change). [authorityVersion] is access-svc's monotonic authority version; each
 * service measures its own lag against it (INV-REQ-1201 behaviour #1).
 */
data class AuthorityChanged(
    val tenant: TenantId,
    val principalId: String,
    val authority: PrincipalAuthority?,
    val authorityVersion: Long,
)

data class AuthorizationRequest(
    val tenant: TenantId,
    val principal: Principal,
    val entityRef: String,
    val action: String,
)

enum class DecisionSource { LOCAL, FALLBACK, DEGRADED }

enum class DecisionReason { GRANTED, NO_GRANT, REVOKED, AUTHORITY_UNAVAILABLE, MACHINE_FLOW_CONTINUES }

data class AuthorityDecision(
    val allowed: Boolean,
    val source: DecisionSource,
    val reason: DecisionReason,
)

/** access-svc's answer to a synchronous decision call (INV-REQ-1201 behaviour #3). */
data class AuthorityVerdict(val allowed: Boolean)

fun interface AuthorityFallback : ReplicaFallback<AuthorizationRequest, AuthorityVerdict>

/**
 * The authority replica (INV-REQ-1201), fed by `AuthorityChanged`.
 *  - lag at most [lagBound] (default one second): decided locally;
 *  - lag above it, or never in contact: one synchronous call to `access-svc`;
 *  - that call fails too: a human or consent-derived agent action is denied, a
 *    machine flow continues. A submission is never denied for want of a decision
 *    (machine tokens are verified offline at the gateway; INV-NOTE-3 §18.6).
 *
 * A definite local or fallback answer is final for every principal: the machine
 * exception covers only the absence of any answer.
 */
class AuthorityReplica(
    private val clock: Clock,
    val lagBound: Duration = DEFAULT_LAG_BOUND,
    private val fallback: ReplicaFallback<AuthorizationRequest, AuthorityVerdict>? = null,
) : ReplicaFeedSink<AuthorityChanged> {
    val replica = EventFedReplica<AuthorityKey, PrincipalAuthority>("authority", clock, lagBound = lagBound)

    fun decide(
        request: AuthorizationRequest,
        deadline: Deadline,
    ): AuthorityDecision {
        if (replica.lag().isWithin(lagBound)) return decideLocally(request)
        return when (val answer = fallback?.fetch(request, deadline) ?: FallbackResult.Unavailable) {
            is FallbackResult.Found ->
                AuthorityDecision(
                    answer.value.allowed,
                    DecisionSource.FALLBACK,
                    if (answer.value.allowed) DecisionReason.GRANTED else DecisionReason.NO_GRANT,
                )

            FallbackResult.NotFound -> AuthorityDecision(false, DecisionSource.FALLBACK, DecisionReason.NO_GRANT)

            FallbackResult.Unavailable -> degraded(request.principal)
        }
    }

    private fun decideLocally(request: AuthorizationRequest): AuthorityDecision {
        val held = replica.peek(AuthorityKey(request.tenant, request.principal.id))
        val now = clock.instant()
        return when {
            held == null -> AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)

            held.revoked -> AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.REVOKED)

            held.grants.any { it.covers(request.entityRef, request.action, now) } ->
                AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED)

            else -> AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)
        }
    }

    private fun degraded(principal: Principal): AuthorityDecision = if (principal.kind == PrincipalKind.MACHINE) {
        AuthorityDecision(true, DecisionSource.DEGRADED, DecisionReason.MACHINE_FLOW_CONTINUES)
    } else {
        AuthorityDecision(false, DecisionSource.DEGRADED, DecisionReason.AUTHORITY_UNAVAILABLE)
    }

    override fun accept(event: AuthorityChanged) {
        val key = AuthorityKey(event.tenant, event.principalId)
        val authority = event.authority
        if (authority == null) {
            replica.apply(ReplicaChange.Remove(key, event.authorityVersion))
        } else {
            replica.apply(ReplicaChange.Upsert(key, authority, event.authorityVersion))
        }
    }

    override fun observeHead(version: Long) = replica.tracker.observeHead(version)

    override fun onSnapshotComplete(version: Long) = replica.tracker.onSnapshotComplete(version)

    override fun onDisconnected() = replica.tracker.onDisconnected()

    fun lag(): Lag = replica.lag()

    companion object {
        /** INV-REQ-1201: lag at most one second is decided locally. */
        val DEFAULT_LAG_BOUND: Duration = Duration.ofSeconds(1)
    }
}
