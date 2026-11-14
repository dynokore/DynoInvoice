package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.aop.support.AopUtils
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.file.Path

/** INV-AC-21107 in the composed application (TPL-18): one controller bean, and the context starts. */
class ExceptionsWiringTest {
    @Test
    fun `INV-AC-21107 the offline context has exactly one ExceptionsController and starts`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(ExceptionsController::class.java).size shouldBe 1
            context.getBeansOfType(ExceptionsProblemAdvice::class.java).size shouldBe 1
        }
    }

    @Test
    fun `INV-AC-21107 the proxied controller of the context answers limit 501 as a contract violation`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            val controller = context.getBean(ExceptionsController::class.java)
            // the generated interface is @Validated: the bean is the CGLIB proxy that runs the method validation
            AopUtils.isAopProxy(controller) shouldBe true
            val mvc =
                MockMvcBuilders
                    .standaloneSetup(controller)
                    .setControllerAdvice(ExceptionsProblemAdvice())
                    .build()
            val request =
                get("/v1/exceptions?limit=501")
                    .requestAttr(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE, "P1")
                    .requestAttr(ConsoleSessionFilter.TENANT_ATTRIBUTE, "T1")
            val response = mvc.perform(request).andReturn().response
            response.status shouldBe ClosedErrorCode.CONTRACT_VIOLATION.httpStatus
            response.contentType shouldContain Problems.CONTENT_TYPE
        }
    }
}
