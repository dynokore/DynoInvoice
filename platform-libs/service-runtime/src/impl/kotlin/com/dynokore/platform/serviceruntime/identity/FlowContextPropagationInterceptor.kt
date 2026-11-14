package com.dynokore.platform.serviceruntime.identity

import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse

/**
 * INV-REQ-1202, owner answer on INV-Q-9241 option B: while the thread holds a [FlowContext] (a verified inbound
 * context, or one a flow entry opened with [FlowContext.begin] and [FlowContext.runWith]), the outbound call to an
 * operation [FlowContextOperations] lists (matched by the method and path of the call with [OperationLines.serving], as
 * the filter matches) carries its signed form unchanged in `X-Flow-Context`. Any other call carries none, and a header a
 * caller set by hand on such a call is removed, so a context is only ever the one the thread holds.
 */
class FlowContextPropagationInterceptor(
    private val operations: FlowContextOperations,
) : ClientHttpRequestInterceptor {
    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        request.headers.remove(FlowContext.HEADER_NAME)
        val context = FlowContext.current()
        if (context != null && operations.serving(request.method.name(), request.uri.rawPath.orEmpty()) != null) {
            request.headers.set(FlowContext.HEADER_NAME, context.compact)
        }
        return execution.execute(request, body)
    }
}
