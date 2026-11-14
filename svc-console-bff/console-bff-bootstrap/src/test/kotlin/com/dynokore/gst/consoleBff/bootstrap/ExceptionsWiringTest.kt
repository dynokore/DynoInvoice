package com.dynokore.gst.consoleBff.bootstrap

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
}
