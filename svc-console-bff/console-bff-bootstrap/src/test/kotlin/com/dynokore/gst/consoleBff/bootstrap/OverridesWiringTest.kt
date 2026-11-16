package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.application.override.OverrideException
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** INV-AC-21206 in the composed application (TPL-18): one controller, one use case, one advice, and the context starts. */
class OverridesWiringTest {
    @Test
    fun `INV-AC-21206 the offline context has exactly one OverridesController over one OverrideException and starts`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(OverridesController::class.java).size shouldBe 1
            context.getBeansOfType(OverrideException::class.java).size shouldBe 1
            context.getBeansOfType(OverridesProblemAdvice::class.java).size shouldBe 1
        }
    }
}
