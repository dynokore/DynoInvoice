package com.dynokore.platform.serviceruntime.resilience.http

import com.dynokore.platform.serviceruntime.resilience.Deadline
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.http.client.ClientHttpResponse
import org.springframework.mock.http.client.MockClientHttpRequest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** API-29: every internal call carries the caller's own remaining budget. */
class DeadlinePropagationInterceptorTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-27T00:00:00Z"), ZoneOffset.UTC)
    private val interceptor = DeadlinePropagationInterceptor(clock)

    @Test
    fun `sets X-Deadline-Ms to the ambient deadline's own remaining budget`() {
        val deadline = Deadline.after(Duration.ofMillis(2_500), clock)
        val request = MockClientHttpRequest()

        CurrentDeadline.callWith(deadline) {
            interceptor.intercept(request, ByteArray(0)) { _, _ -> NotImplementedClientHttpResponse }
        }

        request.headers.getFirst(Deadline.HEADER_NAME) shouldBe "2500"
    }

    @Test
    fun `sends no header at all when no deadline is bound`() {
        val request = MockClientHttpRequest()

        interceptor.intercept(request, ByteArray(0)) { _, _ -> NotImplementedClientHttpResponse }

        request.headers.getFirst(Deadline.HEADER_NAME).shouldBeNull()
    }
}

private object NotImplementedClientHttpResponse : ClientHttpResponse {
    override fun getStatusCode() = org.springframework.http.HttpStatusCode.valueOf(200)

    override fun getStatusText() = "OK"

    override fun close() = Unit

    override fun getBody() = ByteArray(0).inputStream()

    override fun getHeaders() = org.springframework.http.HttpHeaders.EMPTY
}
