package com.dynokore.platform.serviceruntime.identity

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.ClientHttpResponse
import org.springframework.mock.http.client.MockClientHttpRequest

/** INV-REQ-1202 behaviour #1: every internal call carries this workload's own signed token. */
class WorkloadTokenPropagationInterceptorTest {
    @Test
    fun `sets X-Workload-Token from the token source, read fresh on every call`() {
        var current = WorkloadToken("token-a")
        val interceptor = WorkloadTokenPropagationInterceptor(WorkloadTokenSource { current })
        val stubResponse = object : ClientHttpResponse {
            override fun getStatusCode() = HttpStatusCode.valueOf(200)

            override fun getStatusText() = "OK"

            override fun close() = Unit

            override fun getBody() = ByteArray(0).inputStream()

            override fun getHeaders() = HttpHeaders.EMPTY
        }

        val firstRequest = MockClientHttpRequest()
        interceptor.intercept(firstRequest, ByteArray(0)) { _, _ -> stubResponse }
        firstRequest.headers.getFirst(WorkloadToken.HEADER_NAME) shouldBe "token-a"

        current = WorkloadToken("token-b") // rotated between calls
        val secondRequest = MockClientHttpRequest()
        interceptor.intercept(secondRequest, ByteArray(0)) { _, _ -> stubResponse }
        secondRequest.headers.getFirst(WorkloadToken.HEADER_NAME) shouldBe "token-b"
    }
}
