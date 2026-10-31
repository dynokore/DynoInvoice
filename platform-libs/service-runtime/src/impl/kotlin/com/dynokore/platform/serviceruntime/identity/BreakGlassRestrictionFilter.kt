package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.serviceruntime.replica.BreakGlassActionReporter
import com.dynokore.platform.serviceruntime.replica.BreakGlassCounter
import com.dynokore.platform.serviceruntime.replica.BreakGlassSessionView
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.server.PathContainer
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.UUID

/**
 * The restricted break-glass mode (INV-Q-9119 A; owner answers INV-Q-9300 A and INV-Q-9298 B). It acts only on a
 * request that carries an [EndUserPrincipal] with a tenant and no operator session; machine tokens and operator
 * sessions are not touched. When the [BreakGlassSessionView] holds no open session of the tenant with the caller as an
 * approver, the request passes on unchanged. The view is required: a context without one does not start (deny by
 * default, OWASP ASVS V1.4). Otherwise:
 *  - an operation of the lifecycle resource passes on, no report;
 *  - an operation of neither resource is refused 403 `SRT-004`, the chain not called;
 *  - a marked operation is reported once through the [BreakGlassActionReporter] and then passes on; a report that
 *    throws, or no reporter, is 500 `SRT-010` with the chain not called (fail closed, INV-REQ-897).
 * The filter only refuses or passes on, so the decision of a passed request stays with the ordinary authorisation:
 * the session never grants (INV-Q-9300 A).
 */
class BreakGlassRestrictionFilter internal constructor(
    private val view: BreakGlassSessionView,
    private val reporter: ObjectProvider<BreakGlassActionReporter>,
    private val marked: BreakGlassActions,
    private val lifecycle: BreakGlassLifecycleOperations,
    private val counter: BreakGlassCounter,
    private val clock: Clock,
) : Filter {
    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        val http = request as HttpServletRequest
        val principal = http.getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) as? EndUserPrincipal
        val tenant = principal?.tenant
        val now = clock.instant()
        val session =
            if (principal == null || tenant == null || principal.operatorSession != null ||
                http.getAttribute(MachineTokenFilter.PRINCIPAL_ATTRIBUTE) != null
            ) {
                null
            } else {
                view.restrictedSession(tenant, principal.actor, now)
            }
        if (session == null) {
            chain.doFilter(request, response)
            return
        }
        val path = PathContainer.parsePath(http.requestURI.removePrefix(http.contextPath.orEmpty()))
        if (lifecycle.serving(http.method, path) != null) {
            chain.doFilter(request, response)
            return
        }
        val out = response as HttpServletResponse
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
        val line = marked.serving(http.method, path)
        if (line == null) {
            counter.increment(BreakGlassCounter.REFUSED)
            refuse(out, Problems.accessDenied(correlation))
            return
        }
        val reported =
            try {
                checkNotNull(reporter.ifAvailable) { "no BreakGlassActionReporter" }
                    .report(session.unlockRef, line.payload, "${line.method} ${line.template}", now)
                true
            } catch (_: Exception) {
                false
            }
        if (!reported) {
            refuse(out, Problems.serverError(correlation))
            return
        }
        chain.doFilter(request, response)
    }

    private fun refuse(
        response: HttpServletResponse,
        answer: StoredResponse,
    ) {
        response.status = answer.status
        response.contentType = Problems.CONTENT_TYPE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.writer.write(answer.body)
    }
}
