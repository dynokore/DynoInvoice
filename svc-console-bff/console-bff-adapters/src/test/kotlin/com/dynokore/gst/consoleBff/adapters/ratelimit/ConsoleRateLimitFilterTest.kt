package com.dynokore.gst.consoleBff.adapters.ratelimit

import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.domain.ratelimit.ConsoleRateLimitSettings
import com.dynokore.gst.consoleBff.domain.ratelimit.ConsoleRateLimiter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** INV-AC-6924: the servlet filter answers 429 SRT-012 with Retry-After and the API-30 fields, keyed on the session. */
class ConsoleRateLimitFilterTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-11T10:00:00Z"), ZoneOffset.UTC)

    private fun filter(settings: ConsoleRateLimitSettings = ConsoleRateLimitSettings()) =
        ConsoleRateLimitFilter(ConsoleRateLimiter(settings, 1, clock))

    private class Outcome(
        val response: MockHttpServletResponse,
        val chain: MockFilterChain,
    )

    private fun call(
        filter: ConsoleRateLimitFilter,
        principal: String?,
        tenant: String?,
    ): Outcome {
        val request = MockHttpServletRequest("GET", "/api/x").apply { requestURI = "/api/x" }
        principal?.let { request.setAttribute(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE, it) }
        tenant?.let { request.setAttribute(ConsoleSessionFilter.TENANT_ATTRIBUTE, it) }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return Outcome(response, chain)
    }

    @Test
    fun `INV-AC-6924 the 301st request of one principal is 429 with Retry-After, SRT-012 and the API-30 fields`() {
        val filter = filter()
        repeat(300) { call(filter, "p-1", "t-1").response.status shouldBe 200 }
        val allowed = call(filter, "p-2", "t-1").response
        allowed.getHeader("RateLimit-Policy") shouldBe "\"console\";q=300;w=60, \"tenant\";q=3000;w=60"
        allowed.getHeader("RateLimit") shouldBe "\"console\";r=299;t=60, \"tenant\";r=2699;t=60"
        val refused = call(filter, "p-1", "t-1")
        refused.response.status shouldBe 429
        refused.response.getHeader("Retry-After") shouldBe "60"
        refused.response.getHeader("RateLimit-Policy") shouldBe "\"console\";q=300;w=60, \"tenant\";q=3000;w=60"
        refused.response.getHeader("RateLimit") shouldBe "\"console\";r=0;t=60, \"tenant\";r=2698;t=60"
        refused.response.contentType shouldContain "application/problem+json"
        refused.response.contentAsString shouldContain "SRT-012"
        refused.chain.request shouldBe null // the refused request reached nothing behind the filter
    }

    @Test
    fun `INV-AC-6924 the 3001st request of one tenant across principals is 429 with SRT-012`() {
        val filter = filter()
        repeat(3_000) { call(filter, "p-${it / 100}", "t-1").response.status shouldBe 200 }
        val refused = call(filter, "p-new", "t-1").response
        refused.status shouldBe 429
        refused.getHeader("Retry-After") shouldBe "60"
        refused.contentAsString shouldContain "SRT-012"
        refused.getHeader("RateLimit") shouldContain "\"tenant\";r=0;t=60"
        call(filter, "p-new", "t-2").response.status shouldBe 200
    }

    @Test
    fun `INV-AC-6924 a lowered setting takes effect`() {
        val filter = filter(ConsoleRateLimitSettings(perPrincipal = 3))
        repeat(3) { call(filter, "p", "t").response.status shouldBe 200 }
        call(filter, "p", "t").response.status shouldBe 429
    }

    @Test
    fun `INV-AC-6924 a request without a resolved session is not counted and not limited`() {
        val filter = filter(ConsoleRateLimitSettings(perPrincipal = 1, perTenant = 1))
        repeat(5) {
            val out = call(filter, null, null)
            out.response.status shouldBe 200
            out.response.getHeader("RateLimit") shouldBe null
            call(filter, "p", null).response.status shouldBe 200
        }
        call(filter, "p", "t").response.status shouldBe 200
    }
}
