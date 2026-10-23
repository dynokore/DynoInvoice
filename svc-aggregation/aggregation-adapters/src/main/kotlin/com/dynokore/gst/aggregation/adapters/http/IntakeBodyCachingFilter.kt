package com.dynokore.gst.aggregation.adapters.http

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingRequestWrapper

/**
 * Keeps the bytes the message converter read, so that [NotAggregatableAdvice] can tell which JSON token the caller
 * sent as `aggregateType` (a string, a number and an absent member all fail the same way in the generated enum).
 */
@Component
class IntakeBodyCachingFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        chain.doFilter(ContentCachingRequestWrapper(request, CACHE_LIMIT), response)
    }

    private companion object {
        const val CACHE_LIMIT = 1 shl 20
    }
}
