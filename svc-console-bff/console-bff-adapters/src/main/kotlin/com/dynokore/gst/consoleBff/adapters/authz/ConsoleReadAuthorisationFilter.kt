package com.dynokore.gst.consoleBff.adapters.authz

import com.dynokore.gst.consoleBff.adapters.ratelimit.ConsoleRateLimitFilter
import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.application.authz.ReadPermissions
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Authorises every read (GET, HEAD) console-bff serves by the permission key of the route, decided per request by
 * [AuthorityReplica.decide] (INV-REQ-871 behavior 1, INV-Q-10027; OWASP ASVS V4.1.1, V4.1.3; NIST SP 800-53 AC-3).
 * Deny by default: a read with no permission key, a denied decision and a request without the principal the session
 * filter sets never reach the chain, so no owning service is called. The decision is made on every request and never
 * cached here, so a revocation applies to the next read. A DEGRADED decision is whatever `decide` returns. Other
 * methods pass on: the owning service authorises a write again (behavior 2).
 *
 * A read whose table entry names no entity parameter is decided by [AuthorityReplica.decideTenant] (TENANT-scope
 * grant, INV-Q-11688 A); one that names an entity parameter is decided by [AuthorityReplica.decide] at entity scope
 * with that ref, and refused when the request carries no single value for it. No entity ref is invented.
 */
class ConsoleReadAuthorisationFilter(
    private val permissions: ReadPermissions,
    private val replica: AuthorityReplica,
    private val clock: Clock = Clock.systemUTC(),
    private val budget: Duration = Duration.ofSeconds(1),
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(ConsoleReadAuthorisationFilter::class.java)

    private enum class Verdict { PASS, UNAUTHENTICATED, DENIED }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        when (verdictOf(request)) {
            Verdict.PASS -> chain.doFilter(request, response)
            Verdict.UNAUTHENTICATED -> refuse(response, unauthenticated = true)
            Verdict.DENIED -> refuse(response, unauthenticated = false)
        }
    }

    private fun verdictOf(request: HttpServletRequest): Verdict {
        if (request.method != "GET" && request.method != "HEAD") return Verdict.PASS
        val principalId = request.getAttribute(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE) as? String
        val tenant =
            (request.getAttribute(ConsoleSessionFilter.TENANT_ATTRIBUTE) as? String)
                ?.let { runCatching { TenantId.of(it) }.getOrNull() }
        // HEAD is the same read as GET
        val key = permissions.keyFor("GET", request.requestURI)
        return when {
            principalId == null || tenant == null -> {
                Verdict.UNAUTHENTICATED
            }

            key == null -> {
                log.debug("console read refused: no permission key")
                Verdict.DENIED
            }

            else -> {
                val principal = Principal(principalId, PrincipalKind.HUMAN)
                val entityParameter = permissions.entityParameterFor("GET", request.requestURI)
                val decision =
                    if (entityParameter == null) {
                        replica.decideTenant(tenant, principal, key)
                    } else {
                        decideEntity(request, tenant, principal, key, entityParameter)
                    }
                if (decision?.allowed == true) {
                    Verdict.PASS
                } else {
                    log.debug("console read refused: {} {}", decision?.source, decision?.reason)
                    Verdict.DENIED
                }
            }
        }
    }

    /** Null (refused) when the request carries no single non-blank value of the entity parameter. */
    private fun decideEntity(
        request: HttpServletRequest,
        tenant: TenantId,
        principal: Principal,
        key: String,
        entityParameter: String,
    ): AuthorityDecision? {
        val entityRef = request.getParameterValues(entityParameter)?.singleOrNull()?.takeIf { it.isNotBlank() }
        return entityRef?.let {
            replica.decide(AuthorizationRequest(tenant, principal, it, key), Deadline.after(budget, clock))
        }
    }

    private fun refuse(
        response: HttpServletResponse,
        unauthenticated: Boolean,
    ) {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
        val answer = if (unauthenticated) Problems.unauthenticated(correlation) else Problems.accessDenied(correlation)
        response.status = answer.status
        response.contentType = Problems.CONTENT_TYPE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.writer.write(answer.body)
    }

    companion object {
        /** After the session filter and the rate limit filter. */
        const val ORDER: Int = ConsoleRateLimitFilter.ORDER + 1
    }
}
