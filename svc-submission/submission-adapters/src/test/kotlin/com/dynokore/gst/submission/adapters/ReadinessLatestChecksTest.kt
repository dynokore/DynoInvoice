package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.persistence.ReadinessLatestChecks
import com.dynokore.gst.submission.application.readiness.MAX_READINESS_PAGE
import com.dynokore.gst.submission.application.readiness.ReadinessCheckReader
import com.dynokore.gst.submission.application.readiness.StoredReadinessCheck
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.gate.LatestCheck
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/** The readiness-check adapter against a fake reader (INV-AC-22488). */
class ReadinessLatestChecksTest {
    private fun record(seq: Long) =
        StoredReadinessCheck(
            seq,
            StoredReadinessCheck.checkIdOf(seq),
            "E",
            "P",
            if (seq % 2 == 0L) CheckVerdict.READY else CheckVerdict.NOT_READY,
            Instant.parse("2026-04-29T00:00:00Z"),
            "u1",
            null,
        )

    /** 503 records of one period, seq 503 newest; a failure when [fail]. */
    private class FakeReader(
        private val all: List<StoredReadinessCheck>,
        private val fail: Boolean = false,
    ) : ReadinessCheckReader {
        val pages = mutableListOf<Pair<Int, Long?>>()

        override fun latest(
            entityRef: String,
            periodRef: String,
        ): StoredReadinessCheck? = if (fail) error("db down") else all.firstOrNull()

        override fun page(
            entityRef: String,
            periodRef: String,
            limit: Int,
            before: Long?,
        ): List<StoredReadinessCheck> {
            check(!fail) { "db down" }
            pages += limit to before
            return all.filter { before == null || it.seq < before }.take(limit)
        }
    }

    private val newestFirst = (503L downTo 1L).map(::record)

    @Test
    fun `INV-AC-22488 latest maps checkId and verdict of the newest record`() {
        ReadinessLatestChecks(FakeReader(newestFirst)).latest("E", "P") shouldBe
            LatestCheck("chk-503", CheckVerdict.NOT_READY)
    }

    @Test
    fun `INV-AC-22488 latest is null when the period has no record`() {
        ReadinessLatestChecks(FakeReader(emptyList())).latest("E", "P") shouldBe null
    }

    @Test
    fun `INV-AC-22488 knownCheckIds reads every page`() {
        val reader = FakeReader(newestFirst)
        val ids = ReadinessLatestChecks(reader).knownCheckIds("E", "P")
        ids.size shouldBe 503
        ids shouldBe (1L..503L).map { "chk-$it" }.toSet()
        reader.pages shouldBe listOf(MAX_READINESS_PAGE to null, MAX_READINESS_PAGE to 4L)
    }

    @Test
    fun `INV-AC-22488 knownCheckIds of a period with no record is empty after one read`() {
        val reader = FakeReader(emptyList())
        ReadinessLatestChecks(reader).knownCheckIds("E", "P") shouldBe emptySet()
        reader.pages.size shouldBe 1
    }

    @Test
    fun `INV-AC-22488 a failing reader read propagates`() {
        val adapter = ReadinessLatestChecks(FakeReader(newestFirst, fail = true))
        shouldThrow<IllegalStateException> { adapter.latest("E", "P") }
        shouldThrow<IllegalStateException> { adapter.knownCheckIds("E", "P") }
    }
}
