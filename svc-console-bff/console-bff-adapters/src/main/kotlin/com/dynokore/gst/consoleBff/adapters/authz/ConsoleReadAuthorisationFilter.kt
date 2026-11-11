package com.dynokore.gst.consoleBff.adapters.authz

import com.dynokore.gst.consoleBff.adapters.ratelimit.ConsoleRateLimitFilter
import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.application.authz.ReadPermissions
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
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
 * A console read names no entity (the queue spans the entities in the caller's scope), so it is decided at the
 * TENANT level with an empty entity reference, as an operation that names no entity (INV-Q-11688 A).
 */
class ConsoleReadAuthorisationFilter(
    private val permissions: ReadPermissions,
    private val replica: AuthorityReplica,
    private val clock: Clock = Clock.systemUTC(),
    private val budget: Duration = Duration.ofSeconds(1),
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(ConsoleReadAuthorisationFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        if (request.method != "GET" && request.method != "HEAD") {
            chain.doFilter(request, response)
            return
        }
        val principalId = request.getAttribute(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE) as? String
        val tenantText = request.getAttribute(ConsoleSessionFilter.TENANT_ATTRIBUTE) as? String
        val tenant = tenantText?.let { runCatching { TenantId.of(it) }.getOrNull() }
        if (principalId == null || tenant == null) {
            refuse(response, unauthenticated = true)
            return
        }
        // HEAD is the same read as GET
        val key = permissions.keyFor("GET", request.requestURI)
        if (key == null) {
            log.debug("console read refused: no permission key")
            refuse(response, unauthenticated = false)
            return
        }
        val decision =
            replica.decide(
                AuthorizationRequest(
                    tenant,
                    Principal(principalId, PrincipalKind.HUMAN),
                    "",
                    key,
                    level = ScopeLevel.TENANT,
                ),
                Deadline.after(budget, clock),
            )
        if (!decision.allowed) {
            log.debug("console read refused: {} {}", decision.source, decision.reason)
            refuse(response, unauthenticated = false)
            return
        }
        chain.doFilter(request, response)
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
