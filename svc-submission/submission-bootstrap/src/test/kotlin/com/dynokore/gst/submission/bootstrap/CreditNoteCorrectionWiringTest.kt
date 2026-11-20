package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.correction.CreditNoteCorrectionHandler
import com.dynokore.gst.submission.application.correction.CreditNoteTransitions
import com.dynokore.gst.submission.application.correction.NetDerivationRequestPort
import com.dynokore.gst.submission.application.correction.NetRefusedMarks
import com.dynokore.gst.submission.application.correction.NettingDeclarationRecorder
import com.dynokore.gst.submission.application.correction.NettingDeclarations
import com.dynokore.gst.submission.application.correction.OriginalVersionSource
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** INV-AC-25651: the service built as TPL-18 writes it holds one bean of each port and the handler of the path. */
class CreditNoteCorrectionWiringTest {
    @Test
    fun `INV-AC-25651 the context holds exactly one bean of each of the seven types`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.offline(dir).run { context ->
            listOf(
                OriginalVersionSource::class.java,
                NettingDeclarations::class.java,
                NettingDeclarationRecorder::class.java,
                CreditNoteTransitions::class.java,
                NetRefusedMarks::class.java,
                NetDerivationRequestPort::class.java,
                CreditNoteCorrectionHandler::class.java,
            ).forEach { type -> context.getBeansOfType(type).size shouldBe 1 }
            context.getBeansOfType(CreditNoteCorrectionHandler::class.java).keys.single() shouldBe
                "creditNoteCorrectionHandler"
        }
    }
}
