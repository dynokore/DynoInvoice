package com.dynokore.gst.consoleBff.adapters.ratelimit

import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.domain.ratelimit.ConsoleRateLimiter
import com.dynokore.gst.consoleBff.domain.ratelimit.RateLimitFields
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Applies the console and agent protective limits (INV-REQ-825 b5, API-30) to a request whose session
 * [ConsoleSessionFilter] resolved, keyed on the two request attributes it set and never on the cookie. A request that
 * carries no resolved session (the session filter answers those with 401 before this filter runs, or a public path)
 * is not counted. A refusal is 429 with `Retry-After`, the API-20 envelope and `SRT-012`, and has no other effect;
 * allowed responses carry the `RateLimit` and `RateLimit-Policy` fields too. A plan limit never reaches this filter.
 */
class ConsoleRateLimitFilter(
    private val limiter: ConsoleRateLimiter,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val principal = request.getAttribute(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE) as? String
        val tenant = request.getAttribute(ConsoleSessionFilter.TENANT_ATTRIBUTE) as? String
        if (principal == null || tenant == null) {
            chain.doFilter(request, response)
            return
        }
        val decision = limiter.check(principal, tenant)
        val fields = RateLimitFields.of(decision)
        if (decision.allowed) {
            fields.forEach { (name, value) -> response.setHeader(name, value) }
            chain.doFilter(request, response)
            return
        }
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
        val retryAfter = checkNotNull(fields[RateLimitFields.RETRY_AFTER]).toLong()
        val answer = Problems.render(ClosedErrorCode.RATE_LIMITED, correlation, retryAfterSeconds = retryAfter)
        response.status = answer.status
        fields.forEach { (name, value) -> response.setHeader(name, value) }
        answer.headers.forEach { (name, value) -> response.setHeader(name, value) }
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.writer.write(answer.body)
    }

    companion object {
        /** Runs straight after the session filter. */
        const val ORDER: Int = ConsoleSessionFilter.ORDER + 1
    }
}
