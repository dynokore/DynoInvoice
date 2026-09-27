package com.dynokore.platform.serviceruntime.resilience.http

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders

/**
 * INV-REQ-1202 behaviour #2: captures the inbound request's own `Authorization`
 * header as [CurrentEndUserToken] for the rest of the filter chain, so any
 * internal call the handler makes forwards the same end-user token — the
 * owning service re-checks the write itself (INV-REQ-871); this module verifies
 * nothing about the token's contents, only carries it.
 */
class EndUserTokenCaptureFilter : Filter {
    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        val token = (request as HttpServletRequest).getHeader(HttpHeaders.AUTHORIZATION)
        if (token.isNullOrBlank()) {
            chain.doFilter(request, response)
        } else {
            CurrentEndUserToken.callWith(token) { chain.doFilter(request, response) }
        }
    }
}
