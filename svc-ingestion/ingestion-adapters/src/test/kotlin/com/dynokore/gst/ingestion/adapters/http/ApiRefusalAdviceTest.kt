package com.dynokore.gst.ingestion.adapters.http

import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.validation.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders

/** INV-AC-20953: an ApiRefusal thrown from a controller is rendered by the advice in the API-20 envelope. */
class ApiRefusalAdviceTest {
    private val advice = ApiRefusalAdvice()

    @Test
    fun `INV-AC-20953 UNAUTHENTICATED is 401 SRT-003 in the API-20 envelope`() {
        val response = advice.refuse(ApiRefusal(ClosedErrorCode.UNAUTHENTICATED))
        response.statusCode.value() shouldBe 401
        response.headers.getFirst(HttpHeaders.CONTENT_TYPE) shouldContain "json"
        response.body!! shouldContain "\"code\":\"SRT-003\""
        response.body!! shouldContain "\"status\":401"
        response.body!! shouldContain "correlationId"
    }

    @Test
    fun `INV-AC-20953 ACCESS_DENIED is 403 SRT-004 in the API-20 envelope`() {
        val response = advice.refuse(ApiRefusal(ClosedErrorCode.ACCESS_DENIED))
        response.statusCode.value() shouldBe 403
        response.headers.getFirst(HttpHeaders.CONTENT_TYPE) shouldContain "json"
        response.body!! shouldContain "\"code\":\"SRT-004\""
        response.body!! shouldContain "\"status\":403"
        response.body!! shouldContain "correlationId"
    }

    @Test
    fun `a ConstraintViolationException answers 400 SRT-001 in the closed envelope`() {
        val violation = ConstraintViolationException("SECRET-MARKER message at SECRET-MARKER.path", null)
        val response = advice.refuseConstraintViolation(violation)
        response.statusCode.value() shouldBe 400
        response.headers.getFirst(HttpHeaders.CONTENT_TYPE) shouldContain "json"
        response.body!! shouldContain "\"code\":\"SRT-001\""
        response.body!! shouldContain "\"status\":400"
        response.body!! shouldContain "correlationId"
        response.body!! shouldNotContain "SECRET-MARKER"
    }
}
