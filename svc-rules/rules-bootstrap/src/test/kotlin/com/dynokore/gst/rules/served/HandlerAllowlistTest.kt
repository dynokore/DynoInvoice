package com.dynokore.gst.rules.served

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.web.method.HandlerMethod

/** The framework-handler allowlist of the served-operation tests holds one class, by exact name (P140). */
class HandlerAllowlistTest {
    class OtherController {
        fun page() = Unit
    }

    class BasicErrorController {
        fun page() = Unit
    }

    private fun handlerOf(bean: Any) = HandlerMethod(bean, bean.javaClass.getMethod("page"))

    @Test
    fun `the allowlist holds exactly the Spring Boot error controller with a reason`() {
        FRAMEWORK_HANDLERS.keys shouldBe
            setOf("org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController")
        FRAMEWORK_HANDLERS.values.all { it.isNotBlank() } shouldBe true
    }

    @Test
    fun `an unmapped handler outside the allowlist fails naming its method, path and class`() {
        val other = MappedHandler("GET", "/other", handlerOf(OtherController()))
        val failure = shouldThrow<IllegalStateException> { operationsOf(listOf(other), emptyList()) }
        failure.message!! shouldContain "GET /other"
        failure.message!! shouldContain OtherController::class.java.name
    }

    @Test
    fun `a look-alike of the error controller class name is not exempt`() {
        val lookAlike = MappedHandler("GET", "/error", handlerOf(BasicErrorController()))
        shouldThrow<IllegalStateException> { operationsOf(listOf(lookAlike), emptyList()) }
            .message!! shouldContain "GET /error"
    }
}
