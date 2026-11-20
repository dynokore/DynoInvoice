package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.correction.CreditNoteCorrectionHandler
import com.dynokore.gst.submission.application.correction.NetFindings
import com.dynokore.gst.submission.application.correction.NettedAmendmentGuard
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** INV-AC-25529: the service built as TPL-18 writes it starts with the guard and the findings store wired in. */
class NettedAmendmentWiringTest {
    @Test
    fun `INV-AC-25529 the context starts with one guard bean and one NetFindings bean and the correction handler`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.offline(dir).run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(NettedAmendmentGuard::class.java).size shouldBe 1
            context.getBeansOfType(NetFindings::class.java).size shouldBe 1
            context.getBeansOfType(CreditNoteCorrectionHandler::class.java).size shouldBe 1
        }
    }
}
