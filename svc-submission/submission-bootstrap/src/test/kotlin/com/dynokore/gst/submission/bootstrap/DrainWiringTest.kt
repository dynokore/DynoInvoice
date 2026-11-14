package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.drain.DrainConfig
import com.dynokore.gst.submission.application.drain.DrainPeriod
import com.dynokore.gst.submission.application.drain.PreviewDrain
import com.dynokore.gst.submission.application.queue.TransportClient
import com.dynokore.gst.submission.application.readiness.ReadinessInputSource
import com.dynokore.platform.outboxandqueue.DrainOrderAssigner
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** P109 wiring of the drain (INV-REQ-554, INV-AC-20849): the TPL-18 context holds the drain beans, once each. */
class DrainWiringTest {
    private val baseUrl = "inv.transport-client.base-url=http://transport.invalid"

    @Test
    fun `INV-AC-20849 the context holds exactly one bean of each drain type`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.offline(dir).withPropertyValues(baseUrl).run { context ->
            context.getBeansOfType(DrainPeriod::class.java).size shouldBe 1
            context.getBeansOfType(PreviewDrain::class.java).size shouldBe 1
            context.getBeansOfType(DrainOrderAssigner::class.java).size shouldBe 1
            val transport = context.getBeansOfType(TransportClient::class.java)
            transport.size shouldBe 1
            transport.values
                .single()
                .javaClass.simpleName shouldBe "TransportHttpClient"
            val inputs = context.getBeansOfType(ReadinessInputSource::class.java)
            inputs.size shouldBe 1
            inputs.values
                .single()
                .javaClass.simpleName shouldBe "StoredReadinessInputSource"
        }
    }

    @Test
    fun `INV-AC-20849 the drain batch size is read from configuration`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.offline(dir).withPropertyValues(baseUrl).run { context ->
            context.getBean(DrainConfig::class.java).batchSize shouldBe 20
        }
        SubmissionContext.offline(dir).withPropertyValues(baseUrl, "inv.drain.batch-size=7").run { context ->
            context.getBean(DrainConfig::class.java).batchSize shouldBe 7
        }
    }
}
