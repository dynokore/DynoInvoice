package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.correction.CreditNoteCorrectionHandler
import com.dynokore.gst.submission.application.correction.CreditNoteTransitions
import com.dynokore.gst.submission.application.correction.NetDerivationRequestPort
import com.dynokore.gst.submission.application.correction.NetRefusedMarks
import com.dynokore.gst.submission.application.correction.NettingDeclarationRecorder
import com.dynokore.gst.submission.application.correction.NettingDeclarations
import com.dynokore.gst.submission.application.correction.OriginalVersionSource
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
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

    @Test
    fun `INV-AC-25651 the catalogue lists both net derivation events and no look-alike`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.offline(dir).run { context ->
            val catalogue = context.getBean(EventCatalogue::class.java)
            val refused = catalogue.find("com.dynokore.gst.canonical.NetDerivationRefused.v1")
            refused?.topic shouldBe "gst.canonical.net-derivation-refused.v1"
            refused?.producers shouldBe setOf("canonical-svc")
            val requested = catalogue.find("com.dynokore.gst.submission.NetDerivationRequested.v1")
            requested?.topic shouldBe "gst.submission.net-derivation-requested.v1"
            requested?.producers shouldBe setOf("submission-svc")
            catalogue.find("com.dynokore.gst.canonical.NetDerivationRefused.v2") shouldBe null
        }
    }

    @Test
    fun `INV-AC-25651 exactly one inbox consumer subscribes to the refusal topic`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.offline(dir).run { context ->
            val topics =
                context.getBeansOfType(JdbcInboxConsumer::class.java).values.map { consumer ->
                    val field =
                        JdbcInboxConsumer::class.java.getDeclaredField("subscription").apply {
                            isAccessible =
                                true
                        }
                    (field.get(consumer) as ConsumerSubscription).topic
                }
            topics.count { it == "gst.canonical.net-derivation-refused.v1" } shouldBe 1
            topics.count { it == "gst.canonical.net-derivation-refused.v2" } shouldBe 0
        }
    }
}
