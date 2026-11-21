package com.dynokore.gst.rules.bootstrap

import com.dynokore.platform.outboxandqueue.Lane
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.time.Duration

/** The limit of lane REPROCESSING has no default in code (INV-REQ-1004): absent, zero, negative or look-alike keys refuse. */
class ReprocessingLaneLimitsTest {
    private fun env(vararg p: Pair<String, String>) = MockEnvironment().also { e -> p.forEach { e.setProperty(it.first, it.second) } }

    private val permits = ReprocessingConfiguration.PERMITS
    private val window = ReprocessingConfiguration.WINDOW

    @Test
    fun `INV-AC-23934 the limit is read for lane REPROCESSING only`() {
        val limits = ReprocessingConfiguration.laneLimitsOf(env(permits to "5", window to "PT10M"))
        limits.of(Lane.REPROCESSING)!!.permits shouldBe 5
        limits.of(Lane.REPROCESSING)!!.window shouldBe Duration.ofMinutes(10)
        Lane.entries.filter { it != Lane.REPROCESSING }.forEach { limits.of(it) shouldBe null }
    }

    @Test
    fun `INV-AC-23934 an absent, zero, negative, malformed or look-alike value refuses to start`() {
        val bad =
            listOf(
                env(window to "PT10M"),
                env(permits to "5"),
                env(permits to "0", window to "PT10M"),
                env(permits to "-1", window to "PT10M"),
                env(permits to "abc", window to "PT10M"),
                env(permits to "5", window to "PT0S"),
                env(permits to "5", window to "-PT1M"),
                env(permits to "5", window to "10 minutes"),
                env(permits to "5", window to ""),
                env("rules.reprocessing.ratelimit.permits" to "5", "rules.reprocessing.ratelimit.window" to "PT10M"),
                env("rules.reprocessing.rate-limit.Permits" to "5", window to "PT10M"),
            )
        bad.forEach { shouldThrow<Exception> { ReprocessingConfiguration.laneLimitsOf(it) } }
    }
}
