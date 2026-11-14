package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqCompletenessRows
import com.dynokore.gst.submission.adapters.persistence.JooqObligationStartDateStore
import com.dynokore.gst.submission.application.readiness.ReadinessInputSource
import com.dynokore.gst.submission.application.readiness.StoredReadinessInputSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** INV-AC-22208: the bootstrap context has exactly one [ReadinessInputSource], the stored one over the jOOQ ports. */
class ReadinessInputWiringTest {
    private fun field(
        target: Any,
        name: String,
    ): Any? = target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(target)

    @Test
    fun `INV-AC-22208 the context has exactly one ReadinessInputSource and it is the stored one over the jOOQ ports`(
        @TempDir dir: Path,
    ) = SubmissionContext.offline(dir).run { context ->
        val beans = context.getBeansOfType(ReadinessInputSource::class.java)
        beans.keys.toList() shouldBe listOf("readinessInputSource")
        val source = beans.getValue("readinessInputSource")
        source.shouldBeInstanceOf<StoredReadinessInputSource>()
        field(source, "startDates").shouldBeInstanceOf<JooqObligationStartDateStore>()
        field(source, "rows").shouldBeInstanceOf<JooqCompletenessRows>()
    }
}
