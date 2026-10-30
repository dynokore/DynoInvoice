package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingRequestWrapper
import java.util.UUID

/**
 * recordInstallationAction accepts only the installer workload identity (owner ruling INV-Q-3826 A, STK-26): the Job's
 * projected service-account token is the workload token and its subject must equal [installerSubject] exactly. A call
 * without a valid workload token is refused 401 SRT-003; a call under any other workload identity, a call that also
 * carries an end-user `Authorization` token, and every call while [installerSubject] is not configured are refused 403
 * SRT-004 (deny by default). The check runs before the body is read, so a refused caller learns nothing of the request.
 * The request body is cached for the controller, which lists the fields it ignored (API-10).
 */
class InstallerCallFilter(
    private val verifier: WorkloadTokenVerifier,
    private val installerSubject: String,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !(request.method == "POST" && request.requestURI == PATH)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val header = request.getHeader(WorkloadToken.HEADER_NAME)?.takeIf(String::isNotBlank)
        val refusal =
            when (val verification = verifier.verify(header?.let(::WorkloadToken))) {
                is WorkloadVerification.Refused -> {
                    ClosedErrorCode.UNAUTHENTICATED
                }

                is WorkloadVerification.Verified -> {
                    ClosedErrorCode.ACCESS_DENIED.takeUnless {
                        installerSubject.isNotBlank() &&
                            verification.subject == installerSubject &&
                            request.getHeader(HttpHeaders.AUTHORIZATION) == null
                    }
                }
            }
        if (refusal == null) {
            chain.doFilter(ContentCachingRequestWrapper(request, BODY_LIMIT), response)
            return
        }
        val problem =
            Problems.render(
                refusal,
                CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString(),
                null,
            )
        response.status = problem.status
        problem.headers.forEach { (name, value) -> response.setHeader(name, value) }
        response.writer.write(problem.body)
    }

    companion object {
        const val PATH = "/internal/v1/tenancy/installation-actions"
        private const val BODY_LIMIT = 65_536
    }
}
