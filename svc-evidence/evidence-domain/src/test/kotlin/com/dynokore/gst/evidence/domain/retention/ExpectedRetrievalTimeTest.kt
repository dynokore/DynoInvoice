package com.dynokore.gst.evidence.domain.retention

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

class ExpectedRetrievalTimeTest {
    private val limit = Duration.ofSeconds(30)
    private val documented = mapOf("cold" to Duration.ofHours(12), "hot" to Duration.ofSeconds(1))
    private val times = TierRetrievalTimes(documented)

    @Test
    fun `INV-AC-2495 cold lookup with nothing stated shows the documented time`() {
        ExpectedRetrievalTime.notice("cold", times, limit) shouldBe
            RetrievalNotice.ShowExpectedTime(Duration.ofHours(12))
    }

    @Test
    fun `hot tier within the limit shows nothing`() {
        ExpectedRetrievalTime.notice("hot", times, limit) shouldBe RetrievalNotice.WithinInteractiveLimit
    }

    @Test
    fun `stated time replaces the documented time`() {
        val stated = TierRetrievalTimes(documented, mapOf("cold" to Duration.ofHours(4)))
        ExpectedRetrievalTime.notice("cold", stated, limit) shouldBe
            RetrievalNotice.ShowExpectedTime(Duration.ofHours(4))
    }

    @Test
    fun `stated time within the limit shows nothing`() {
        val stated = TierRetrievalTimes(documented, mapOf("cold" to Duration.ofSeconds(10)))
        ExpectedRetrievalTime.notice("cold", stated, limit) shouldBe RetrievalNotice.WithinInteractiveLimit
    }

    @Test
    fun `time equal to the limit is within it`() {
        ExpectedRetrievalTime.notice("cold", TierRetrievalTimes(mapOf("cold" to limit)), limit) shouldBe
            RetrievalNotice.WithinInteractiveLimit
    }

    @Test
    fun `time one millisecond over the limit is shown`() {
        val over = limit.plusMillis(1)
        ExpectedRetrievalTime.notice("cold", TierRetrievalTimes(mapOf("cold" to over)), limit) shouldBe
            RetrievalNotice.ShowExpectedTime(over)
    }

    @Test
    fun `look-alike tier Cold is rejected`() {
        shouldThrow<IllegalArgumentException> { ExpectedRetrievalTime.notice("Cold", times, limit) }
    }

    @Test
    fun `undeclared tier is rejected`() {
        shouldThrow<IllegalArgumentException> { ExpectedRetrievalTime.notice("archive", times, limit) }
    }

    @Test
    fun `blank tier is rejected`() {
        shouldThrow<IllegalArgumentException> { ExpectedRetrievalTime.notice(" ", times, limit) }
    }

    @Test
    fun `stated key that differs only by case is rejected`() {
        shouldThrow<IllegalArgumentException> {
            TierRetrievalTimes(documented, mapOf("Cold" to Duration.ofHours(1)))
        }
    }

    @Test
    fun `stated zero is rejected`() {
        shouldThrow<IllegalArgumentException> {
            TierRetrievalTimes(documented, mapOf("cold" to Duration.ZERO))
        }
    }

    @Test
    fun `negative documented time is rejected`() {
        shouldThrow<IllegalArgumentException> {
            TierRetrievalTimes(mapOf("cold" to Duration.ofSeconds(-1)))
        }
    }

    @Test
    fun `blank tier key is rejected`() {
        shouldThrow<IllegalArgumentException> { TierRetrievalTimes(mapOf(" " to Duration.ofSeconds(1))) }
    }

    @Test
    fun `negative limit is rejected`() {
        shouldThrow<IllegalArgumentException> {
            ExpectedRetrievalTime.notice("cold", times, Duration.ofSeconds(-1))
        }
    }
}
