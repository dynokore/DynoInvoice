package com.dynokore.platform.serviceruntime.identity

import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse

/** INV-REQ-1202 behaviour #1: every internal call carries this workload's own signed token. */
class WorkloadTokenPropagationInterceptor(private val tokenSource: WorkloadTokenSource) : ClientHttpRequestInterceptor {
    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        request.headers.set(WorkloadToken.HEADER_NAME, tokenSource.current().value)
        return execution.execute(request, body)
    }
}
