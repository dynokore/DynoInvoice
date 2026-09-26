package com.dynokore.platform.adapterruntime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class TimeBasedCutoverRuleTest {
    private val before = AdapterVersion("1.0.0")
    private val after = AdapterVersion("2.0.0")
    private val cutoverAt = Instant.parse("2026-10-01T00:00:00Z")
    private val rule = TimeBasedCutoverRule(before, after, cutoverAt)

    @Test
    fun `before the cutover instant routes to the old version`() {
        rule.activeVersion(cutoverAt.minusSeconds(1)) shouldBe before
    }

    @Test
    fun `at the cutover instant routes to the new version (INV-REQ-1354)`() {
        rule.activeVersion(cutoverAt) shouldBe after
    }

    @Test
    fun `after the cutover instant routes to the new version`() {
        rule.activeVersion(cutoverAt.plusSeconds(1)) shouldBe after
    }
}
