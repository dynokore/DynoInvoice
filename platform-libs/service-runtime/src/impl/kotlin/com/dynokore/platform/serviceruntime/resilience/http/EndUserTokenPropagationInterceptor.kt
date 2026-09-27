package com.dynokore.platform.serviceruntime.resilience.http

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse

/**
 * INV-REQ-1202 behaviour #2: forwards the ambient [CurrentEndUserToken] — the
 * token of the request this call is made on behalf of — as this call's own
 * `Authorization` header, unless the caller already set one explicitly. Never
 * logs the token (CS-34).
 */
class EndUserTokenPropagationInterceptor : ClientHttpRequestInterceptor {
    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        CurrentEndUserToken.current()?.let { token ->
            if (!request.headers.containsHeader(HttpHeaders.AUTHORIZATION)) {
                request.headers.set(HttpHeaders.AUTHORIZATION, token)
            }
        }
        return execution.execute(request, body)
    }
}
