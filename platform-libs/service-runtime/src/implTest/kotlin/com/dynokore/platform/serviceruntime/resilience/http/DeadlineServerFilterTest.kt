package com.dynokore.platform.serviceruntime.resilience.http

import com.dynokore.platform.serviceruntime.resilience.Deadline
import io.kotest.matchers.shouldBe
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** INV-REQ-1203, API-29: reconstructs the caller's remaining budget and binds it for the rest of the chain. */
class DeadlineServerFilterTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-27T00:00:00Z"), ZoneOffset.UTC)
    private val filter = DeadlineServerFilter(clock, defaultBudget = Duration.ofSeconds(30))

    @Test
    fun `reconstructs the deadline from X-Deadline-Ms and binds it for the chain's duration`() {
        val request = MockHttpServletRequest().apply { addHeader(Deadline.HEADER_NAME, "1200") }
        val response = MockHttpServletResponse()
        var seenDuringChain: Deadline? = null

        filter.doFilter(request, response, FilterChain { _, _ -> seenDuringChain = CurrentDeadline.current() })

        seenDuringChain shouldBe Deadline.ofRemainingMillis(1200, clock)
        CurrentDeadline.current() shouldBe null // not bound outside the chain
    }

    @Test
    fun `a request with no header at all starts a fresh chain with the default budget`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        var seenDuringChain: Deadline? = null

        filter.doFilter(request, response, FilterChain { _, _ -> seenDuringChain = CurrentDeadline.current() })

        seenDuringChain shouldBe Deadline.after(Duration.ofSeconds(30), clock)
    }
}
