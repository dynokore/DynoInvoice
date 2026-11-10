package com.dynokore.gst.consoleBff.adapters.session

import com.dynokore.gst.consoleBff.application.session.RefusalReason
import com.dynokore.gst.consoleBff.application.session.SessionResolution
import com.dynokore.gst.consoleBff.application.session.SessionResolver
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Resolves the session cookie of every request on the main port (OWASP ASVS V3). Fail closed: every path needs a
 * session except an exact-match member of [PUBLIC_PATHS], which is empty here. A refusal answers 401 with the API-20
 * envelope and `SRT-003`, with no other effect; only the reason name is logged.
 */
class ConsoleSessionFilter(
    private val resolver: SessionResolver,
    private val publicPaths: Set<String> = PUBLIC_PATHS,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(ConsoleSessionFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        // the identity of this request is this filter's own result, never what arrived with it
        request.removeAttribute(PRINCIPAL_ATTRIBUTE)
        request.removeAttribute(TENANT_ATTRIBUTE)
        if (request.requestURI in publicPaths) {
            chain.doFilter(request, response)
            return
        }
        val cookies = request.cookies?.filter { it.name == SessionCookie.NAME }.orEmpty()
        val resolution =
            when (cookies.size) {
                0 -> SessionResolution.Refused(RefusalReason.NO_COOKIE)
                1 -> resolver.resolve(cookies.single().value)
                else -> SessionResolution.Refused(RefusalReason.MALFORMED)
            }
        when (resolution) {
            is SessionResolution.Resolved -> {
                request.setAttribute(PRINCIPAL_ATTRIBUTE, resolution.principalId)
                request.setAttribute(TENANT_ATTRIBUTE, resolution.tenantId)
                chain.doFilter(request, response)
            }

            is SessionResolution.Refused -> {
                log.debug("console session refused: {}", resolution.reason.name)
                val correlation = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
                val answer = Problems.unauthenticated(correlation)
                response.status = answer.status
                response.contentType = Problems.CONTENT_TYPE
                response.characterEncoding = StandardCharsets.UTF_8.name()
                response.writer.write(answer.body)
            }
        }
    }

    companion object {
        /** Exact request paths that need no session; empty until a task adds one (fail closed). */
        val PUBLIC_PATHS: Set<String> = emptySet()

        /** The filter order; INV-T-4109 places its limit filter after it. */
        const val ORDER: Int = 10

        const val PRINCIPAL_ATTRIBUTE = "console.session.principalId"
        const val TENANT_ATTRIBUTE = "console.session.tenantId"
    }
}
