package com.dynokore.gst.ingestion.adapters.http

import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** INV-AC-20953: an ApiRefusal thrown from a controller is rendered in the API-20 envelope. */
class ApiRefusalAdviceTest {
    @RestController
    class Thrower {
        @GetMapping("/unauthenticated")
        fun unauthenticated(): String = throw ApiRefusal(ClosedErrorCode.UNAUTHENTICATED)

        @GetMapping("/denied")
        fun denied(): String = throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
    }

    private val mvc = MockMvcBuilders.standaloneSetup(Thrower()).setControllerAdvice(ApiRefusalAdvice()).build()

    @Test
    fun `INV-AC-20953 UNAUTHENTICATED is 401 SRT-003 in the API-20 envelope`() {
        val response = mvc.perform(get("/unauthenticated")).andReturn().response
        response.status shouldBe 401
        MediaType.parseMediaType(response.contentType!!).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) shouldBe true
        response.contentAsString shouldContain "SRT-003"
    }

    @Test
    fun `INV-AC-20953 ACCESS_DENIED is 403 SRT-004 in the API-20 envelope`() {
        val response = mvc.perform(get("/denied")).andReturn().response
        response.status shouldBe 403
        MediaType.parseMediaType(response.contentType!!).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) shouldBe true
        response.contentAsString shouldContain "SRT-004"
    }
}
