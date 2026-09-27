package com.dynokore.platform.serviceruntime.resilience.http

import com.dynokore.platform.serviceruntime.resilience.Deadline
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import java.time.Clock

/**
 * API-29: every internal call carries `X-Deadline-Ms`, the caller's own
 * remaining budget — the [CurrentDeadline] a `ResilientEdge`'s own `execute`
 * binds around this very call (INV-REQ-1203). A client called with no ambient
 * deadline bound — outside a `ResilientEdge.execute`, which every §2.5 edge is
 * required to use (ARC-08) — sends no header at all rather than a made-up
 * value: a call site to fix, not a value for this interceptor to invent.
 */
class DeadlinePropagationInterceptor(private val clock: Clock) : ClientHttpRequestInterceptor {
    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        CurrentDeadline.current()?.let { deadline ->
            request.headers.set(Deadline.HEADER_NAME, deadline.remaining(clock).toMillis().toString())
        }
        return execution.execute(request, body)
    }
}
