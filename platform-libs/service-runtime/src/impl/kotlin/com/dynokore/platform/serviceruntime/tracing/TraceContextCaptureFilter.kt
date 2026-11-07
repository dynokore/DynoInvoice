package com.dynokore.platform.serviceruntime.tracing

import com.dynokore.platform.serviceruntime.logging.TraceParent
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest

/**
 * Captures the inbound `traceparent` as [CurrentTraceParent] when [TraceParent.parse] accepts it; a header it refuses
 * (all-zero ids, wrong length, upper-case hex) is ignored, so the next outbound call starts a new trace.
 */
class TraceContextCaptureFilter : Filter {
    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        val parent = (request as HttpServletRequest).getHeader(HEADER)?.let(TraceParent::parse)
        if (parent == null) {
            chain.doFilter(request, response)
        } else {
            CurrentTraceParent.callWith(parent) { chain.doFilter(request, response) }
        }
    }

    companion object {
        const val HEADER = "traceparent"
    }
}
