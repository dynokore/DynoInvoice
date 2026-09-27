package com.dynokore.platform.serviceruntime.identity

import io.kotest.matchers.shouldBe
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * INV-AC-2200: "given `canonical-svc` running; when it is called on its record
 * API once with a valid workload token and once without; then the call with the
 * token is accepted; the call without it is refused as unauthenticated" — a
 * small fixture standing in for that record API's own filter chain (its
 * business handler is irrelevant to this scenario, which is entirely about
 * whether the chain is reached at all), never a live service (coordinator
 * guidance: no `canonical-svc` code exists yet to run against).
 */
class WorkloadTokenAuthenticationFilterTest {
    private fun filter(verified: Boolean) = WorkloadTokenAuthenticationFilter(
        WorkloadTokenVerifier { token ->
            if (verified) WorkloadVerification.Verified(subject = "calling-svc") else WorkloadVerification.Refused("invalid")
        },
    )

    @Test
    fun `a call to the record API with a valid workload token is accepted`() {
        val request = MockHttpServletRequest().apply { addHeader(WorkloadToken.HEADER_NAME, "a-valid-token") }
        val response = MockHttpServletResponse()
        var chainReached = false

        filter(verified = true).doFilter(request, response, FilterChain { _, _ -> chainReached = true })

        chainReached shouldBe true
        response.status shouldBe HttpServletResponse.SC_OK // MockHttpServletResponse's own default: nothing was written against it
    }

    @Test
    fun `a call to the record API with no workload token at all is refused as unauthenticated`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        var chainReached = false

        filter(verified = false).doFilter(request, response, FilterChain { _, _ -> chainReached = true })

        chainReached shouldBe false
        response.status shouldBe HttpServletResponse.SC_UNAUTHORIZED
    }

    @Test
    fun `a call with an invalid workload token is refused the same way as a missing one`() {
        val request = MockHttpServletRequest().apply { addHeader(WorkloadToken.HEADER_NAME, "tampered") }
        val response = MockHttpServletResponse()
        var chainReached = false

        filter(verified = false).doFilter(request, response, FilterChain { _, _ -> chainReached = true })

        chainReached shouldBe false
        response.status shouldBe HttpServletResponse.SC_UNAUTHORIZED
    }
}
