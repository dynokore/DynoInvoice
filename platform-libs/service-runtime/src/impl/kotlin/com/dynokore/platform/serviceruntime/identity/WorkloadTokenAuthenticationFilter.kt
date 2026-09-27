package com.dynokore.platform.serviceruntime.identity

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory

/**
 * INV-AC-2200, STK-26: refuses a request carrying no valid workload token
 * before it reaches any handler — "no hop looks up user grants remotely"
 * (INV-REQ-1202) starts with this hop trusting no caller it has not itself
 * verified. A [WorkloadVerification.Refused] reason is logged at this, the
 * refusing hop, as a label only — never the token itself (CS-34).
 */
class WorkloadTokenAuthenticationFilter(private val verifier: WorkloadTokenVerifier) : Filter {
    private val log = LoggerFactory.getLogger(WorkloadTokenAuthenticationFilter::class.java)

    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        val http = request as HttpServletRequest
        val header = http.getHeader(WorkloadToken.HEADER_NAME)?.takeIf(String::isNotBlank)
        when (val verification = verifier.verify(header?.let(::WorkloadToken))) {
            is WorkloadVerification.Verified -> chain.doFilter(request, response)

            is WorkloadVerification.Refused -> {
                log.debug("workload token refused: {}", verification.reason)
                (response as HttpServletResponse).sendError(HttpServletResponse.SC_UNAUTHORIZED, "workload token refused")
            }
        }
    }
}
