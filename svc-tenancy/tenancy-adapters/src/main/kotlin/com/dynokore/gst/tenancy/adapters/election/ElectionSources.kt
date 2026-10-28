package com.dynokore.gst.tenancy.adapters.election

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.election.EntityFactSource
import com.dynokore.gst.tenancy.domain.FactAnswer
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Clock
import java.time.Duration

/**
 * The default binding of the entity facts: every fact is NOT_RECORDED (owner answer INV-Q-9696 B), so
 * APPORTIONMENT_MODE and EXCHANGE_RATE_SOURCE stay OUTSTANDING and POSTING_DATE is refused until INV-T-4933 binds
 * the stored answers.
 */
class UnrecordedEntityFacts : EntityFactSource {
    override fun recordsPurchases(entityRef: String) = FactAnswer.NOT_RECORDED

    override fun hasForeignCurrencyDocuments(entityRef: String) = FactAnswer.NOT_RECORDED

    override fun claimsInputTaxOnPostingBasis(entityRef: String) = FactAnswer.NOT_RECORDED
}

/** The caller of the request in hand: the `sub` of the verified end-user token (API-24); no token is 401 SRT-003. */
class RequestCallerSource(
    private val principals: EndUserPrincipalResolver,
) : CallerSource {
    override fun actor(): String {
        val request =
            (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
                ?: throw ApiRefusal(ClosedErrorCode.UNAUTHENTICATED)
        val principal =
            principals.resolve(request.getHeader("Authorization"))
                ?: throw ApiRefusal(ClosedErrorCode.UNAUTHENTICATED)
        return principal.actor
    }
}

/**
 * [ActionAuthority] over the authority replica (ruling INV-Q-10025): true only when the replica decides allowed for
 * the person (kind HUMAN), the caller's tenant, the entity and the action, with source LOCAL or FALLBACK. tenancy-svc
 * has no access-svc fallback (`x-sync-edge` of `authorise` is `any-except:tenancy-svc->access-svc`), so beyond the lag
 * bound, with no replica bound, or with a DEGRADED answer the person is refused (fail closed).
 */
class ReplicaActionAuthority(
    private val replica: AuthorityReplica?,
    private val tenants: TenantContext,
    private val clock: Clock = Clock.systemUTC(),
    private val budget: Duration = Duration.ofSeconds(2),
) : ActionAuthority {
    override fun holds(
        person: String,
        entityRef: String,
        action: String,
    ): Boolean {
        val decision =
            replica?.decide(
                AuthorizationRequest(tenants.current(), Principal(person, PrincipalKind.HUMAN), entityRef, action),
                Deadline.after(budget, clock),
            ) ?: return false
        return decision.allowed && decision.source in ANSWERED
    }

    private companion object {
        val ANSWERED = setOf(DecisionSource.LOCAL, DecisionSource.FALLBACK)
    }
}
