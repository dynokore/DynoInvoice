package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.serviceruntime.logging.CorrelationId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

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
    private fun filter(verified: Boolean) =
        WorkloadTokenAuthenticationFilter(
            WorkloadTokenVerifier { token ->
                if (verified) {
                    WorkloadVerification.Verified(
                        subject = "calling-svc",
                    )
                } else {
                    WorkloadVerification.Refused("invalid")
                }
            },
        )

    @Test
    fun `a call to the record API with a valid workload token is accepted`() {
        val request = MockHttpServletRequest().apply { addHeader(WorkloadToken.HEADER_NAME, "a-valid-token") }
        val response = MockHttpServletResponse()
        var chainReached = false

        filter(verified = true).doFilter(request, response, FilterChain { _, _ -> chainReached = true })

        chainReached shouldBe true
        // MockHttpServletResponse's own default: nothing was written against it
        response.status shouldBe HttpServletResponse.SC_OK
    }

    private class Refusal(
        val response: MockHttpServletResponse,
        val chainReached: Boolean,
    ) {
        val body: String get() = response.contentAsString
        val json: JsonNode get() = ObjectMapper().readTree(body)
    }

    private fun refuse(
        header: String?,
        verified: Boolean = false,
    ): Refusal {
        val request = MockHttpServletRequest().apply { header?.let { addHeader(WorkloadToken.HEADER_NAME, it) } }
        val response = MockHttpServletResponse()
        var chainReached = false
        filter(verified).doFilter(request, response, FilterChain { _, _ -> chainReached = true })
        return Refusal(response, chainReached)
    }

    private fun assertSrt003(refusal: Refusal) {
        val r = refusal.response
        refusal.chainReached shouldBe false
        r.status shouldBe HttpServletResponse.SC_UNAUTHORIZED
        r.contentType!! shouldStartWith Problems.CONTENT_TYPE
        r.characterEncoding shouldBe "UTF-8"
        r.errorMessage shouldBe null
        val json = refusal.json
        val expected = Problems.unauthenticated("x")
        json["code"].asString() shouldBe "SRT-003"
        json["status"].intValue() shouldBe 401
        json["title"].asString() shouldBe "Not authenticated"
        json["disposition"].asString() shouldBe "TERMINAL"
        json["severity"].asString() shouldBe ObjectMapper().readTree(expected.body)["severity"].asString()
        json["remediationOwner"].asString() shouldBe "Platform"
        json["type"].asString() shouldBe ObjectMapper().readTree(expected.body)["type"].asString()
        refusal.body shouldBe Problems.unauthenticated(json["correlationId"].asString()).body
    }

    @Test
    fun `INV-AC-2200 a call to the record API with no workload token at all is refused as unauthenticated`() {
        assertSrt003(refuse(header = null))
    }

    @Test
    fun `INV-AC-2200 a call with a blank workload token is refused as unauthenticated`() {
        assertSrt003(refuse(header = "   "))
    }

    @Test
    fun `INV-AC-2200 a call with an invalid workload token is refused the same way as a missing one`() {
        assertSrt003(refuse(header = "tampered"))
    }

    @Test
    fun `a refusal inside a correlation carries that correlation id`() {
        val refusal = CorrelationContext.withCorrelation(CorrelationId("corr-1")) { refuse(header = null) }

        refusal.json["correlationId"].asString() shouldBe "corr-1"
    }

    @Test
    fun `a refusal outside a correlation carries a fresh http-prefixed id each time`() {
        val first = refuse(header = null).json["correlationId"].asString()
        val second = refuse(header = null).json["correlationId"].asString()

        first shouldMatch Regex("^http:[0-9a-f-]{36}$")
        second shouldMatch Regex("^http:[0-9a-f-]{36}$")
        (first == second) shouldBe false
    }
}
