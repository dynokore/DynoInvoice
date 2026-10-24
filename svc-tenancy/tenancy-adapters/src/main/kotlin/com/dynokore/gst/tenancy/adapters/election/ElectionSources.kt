package com.dynokore.gst.tenancy.adapters.election

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.election.EntityFactSource
import com.dynokore.gst.tenancy.application.election.RoleSource
import com.dynokore.gst.tenancy.domain.ElectionRoles
import com.dynokore.gst.tenancy.domain.FactAnswer
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.replica.AuthorityKey
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Instant

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
 * The roles a person holds for an entity according to the authority replica (CS-23): the actions of the person's
 * grants on the exact entity that are valid now and are election roles. Exact equality on the person's id; nothing is
 * answered for a person the replica does not hold, and an unavailable replica answers nothing (fail closed).
 */
class AuthorityRoleSource(
    private val replica: AuthorityReplica?,
    private val tenants: TenantContext,
    private val now: () -> Instant = Instant::now,
) : RoleSource {
    override fun rolesOf(
        person: String,
        entityRef: String,
    ): Set<String> {
        val state = replica?.grants?.peek(AuthorityKey(tenants.current(), person, entityRef)) ?: return emptySet()
        val at = now()
        return state.grants
            .filter { it.isValidAt(at) && it.action in ROLES }
            .map { it.action }
            .toSet()
    }

    private companion object {
        val ROLES = setOf(ElectionRoles.ENTITY_ADMIN, ElectionRoles.TENANT_ADMIN, ElectionRoles.TAX_APPROVER)
    }
}
