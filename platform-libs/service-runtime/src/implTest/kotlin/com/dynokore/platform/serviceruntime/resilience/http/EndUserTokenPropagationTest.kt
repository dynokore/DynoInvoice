package com.dynokore.platform.serviceruntime.resilience.http

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.ClientHttpResponse
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/** INV-REQ-1202 behaviour #2: the end-user token travels from the inbound request to every internal call it causes. */
class EndUserTokenPropagationTest {
    @Test
    fun `EndUserTokenCaptureFilter binds the inbound Authorization header for the chain's duration`() {
        val request = MockHttpServletRequest().apply { addHeader(HttpHeaders.AUTHORIZATION, "Bearer end-user-token") }
        val response = MockHttpServletResponse()
        var seenDuringChain: String? = null

        EndUserTokenCaptureFilter().doFilter(request, response, FilterChain { _, _ -> seenDuringChain = CurrentEndUserToken.current() })

        seenDuringChain shouldBe "Bearer end-user-token"
        CurrentEndUserToken.current().shouldBeNull() // not bound outside the chain
    }

    @Test
    fun `EndUserTokenCaptureFilter binds nothing for a request with no Authorization header`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        var seenDuringChain: String? = "not yet observed"

        EndUserTokenCaptureFilter().doFilter(request, response, FilterChain { _, _ -> seenDuringChain = CurrentEndUserToken.current() })

        seenDuringChain.shouldBeNull()
    }

    @Test
    fun `EndUserTokenPropagationInterceptor forwards the ambient token as Authorization`() {
        val request = MockClientHttpRequest()

        CurrentEndUserToken.callWith("Bearer end-user-token") {
            EndUserTokenPropagationInterceptor().intercept(request, ByteArray(0)) { _, _ -> stubResponse }
        }

        request.headers.getFirst(HttpHeaders.AUTHORIZATION) shouldBe "Bearer end-user-token"
    }

    @Test
    fun `EndUserTokenPropagationInterceptor never overrides a call's own explicit Authorization`() {
        val request = MockClientHttpRequest().apply { headers.set(HttpHeaders.AUTHORIZATION, "Bearer workload-own-call") }

        CurrentEndUserToken.callWith("Bearer end-user-token") {
            EndUserTokenPropagationInterceptor().intercept(request, ByteArray(0)) { _, _ -> stubResponse }
        }

        request.headers.getFirst(HttpHeaders.AUTHORIZATION) shouldBe "Bearer workload-own-call"
    }

    private val stubResponse = object : ClientHttpResponse {
        override fun getStatusCode() = HttpStatusCode.valueOf(200)

        override fun getStatusText() = "OK"

        override fun close() = Unit

        override fun getBody() = ByteArray(0).inputStream()

        override fun getHeaders() = HttpHeaders.EMPTY
    }
}
