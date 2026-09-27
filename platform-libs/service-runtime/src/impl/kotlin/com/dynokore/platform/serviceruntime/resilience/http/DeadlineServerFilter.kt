package com.dynokore.platform.serviceruntime.resilience.http

import com.dynokore.platform.serviceruntime.resilience.Deadline
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import java.time.Clock
import java.time.Duration

/**
 * API-29, INV-REQ-1203: reconstructs the caller's remaining budget from
 * `X-Deadline-Ms` and binds it as this request's [CurrentDeadline] for the rest
 * of the filter chain, so the handler can read it, thread it explicitly down to
 * its own adapters (TPL-07's `deadline: Deadline` parameter), and any further
 * internal call it makes propagates what is left, not a fresh budget.
 *
 * A request with no header at all is the entry point of a chain — an external
 * request, or a job that starts one — so it is given [defaultBudget] rather than
 * a missing deadline: INV-REQ-1203 requires every synchronous call to carry
 * one, and refusing the request outright for lacking a header no caller outside
 * the mesh would ever send is worse. [defaultBudget] is deliberately generous;
 * an entry-point service's own use case sets a tighter one itself where §36.6
 * names one for its own edge.
 */
class DeadlineServerFilter(
    private val clock: Clock,
    private val defaultBudget: Duration,
) : Filter {
    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        val remainingMillis = (request as HttpServletRequest).getHeader(Deadline.HEADER_NAME)?.toLongOrNull()
        val deadline = remainingMillis
            ?.let { Deadline.ofRemainingMillis(it, clock) }
            ?: Deadline.after(defaultBudget, clock)
        CurrentDeadline.callWith(deadline) { chain.doFilter(request, response) }
    }
}
