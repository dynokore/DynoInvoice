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
 *
 * For a verified token it also sets [ServiceCaller.REQUEST_ATTRIBUTE] to the service whose configured subject equals
 * the verified subject (exact [String] equality), and nothing otherwise (INV-AC-8251). Only `X-Workload-Token` is read.
 */
class WorkloadTokenAuthenticationFilter(
    private val verifier: WorkloadTokenVerifier,
    callers: List<WorkloadCaller> = emptyList(),
) : Filter {
    private val serviceBySubject: Map<String, String> = callers.associate { it.subject to it.service }

    private val log = LoggerFactory.getLogger(WorkloadTokenAuthenticationFilter::class.java)

    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        val http = request as HttpServletRequest
        // The identity of the caller is this filter's own result for this request, never what arrived with it.
        http.removeAttribute(ServiceCaller.REQUEST_ATTRIBUTE)
        val header = http.getHeader(WorkloadToken.HEADER_NAME)?.takeIf(String::isNotBlank)
        when (val verification = verifier.verify(header?.let(::WorkloadToken))) {
            is WorkloadVerification.Verified -> {
                serviceBySubject[verification.subject]?.let { http.setAttribute(ServiceCaller.REQUEST_ATTRIBUTE, it) }
                chain.doFilter(request, response)
            }

            is WorkloadVerification.Refused -> {
                log.debug("workload token refused: {}", verification.reason)
                (response as HttpServletResponse).sendError(
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "workload token refused",
                )
            }
        }
    }
}
