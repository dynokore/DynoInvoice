package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqHoldingChecks
import com.dynokore.gst.submission.adapters.persistence.JooqOverrideStore
import com.dynokore.gst.submission.adapters.persistence.JooqReleasedOverrideChecks
import com.dynokore.gst.submission.application.gate.OverrideStore
import com.dynokore.gst.submission.application.readiness.HoldingChecks
import com.dynokore.gst.submission.application.readiness.ReadinessEntrySource
import com.dynokore.gst.submission.application.readiness.ReleasedOverrideChecks
import com.dynokore.gst.submission.application.readiness.RunReadiness
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** INV-AC-20556: the service built as TPL-18 writes it declares the three beans over the jOOQ classes. */
class OverrideWiringTest {
    @Test
    fun `INV-AC-20556 the three beans exist and the override store is the jOOQ class`(
        @TempDir dir: Path,
    ) {
        SubmissionContext.offline(dir).run { context ->
            context.getBean(OverrideStore::class.java).shouldBeInstanceOf<JooqOverrideStore>()
            context.getBean(HoldingChecks::class.java).shouldBeInstanceOf<JooqHoldingChecks>()
            context.getBean(ReleasedOverrideChecks::class.java).shouldBeInstanceOf<JooqReleasedOverrideChecks>()
            context.getBeansOfType(RunReadiness::class.java).size shouldBe 0
            context.getBeansOfType(ReadinessEntrySource::class.java).size shouldBe 0
        }
    }
}
