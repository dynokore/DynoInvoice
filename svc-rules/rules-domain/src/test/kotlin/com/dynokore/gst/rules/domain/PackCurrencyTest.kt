package com.dynokore.gst.rules.domain

import com.dynokore.gst.rules.domain.pack.EngineCompatibility
import com.dynokore.gst.rules.domain.pack.PackCurrency
import com.dynokore.gst.rules.domain.pack.SilenceLimit
import com.dynokore.gst.rules.domain.pack.UnactivatedPack
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** DEP-001 (INV-REQ-1080, INV-REQ-2468) and the DEP-003 range check, as pure functions of the day they are given. */
class PackCurrencyTest {
    private val reached = LocalDate.of(2026, 11, 1)
    private val pack = UnactivatedPack("SCOPE-pack", "2026.12", LocalDate.of(2026, 12, 1))

    @Test
    fun `INV-AC-2497 DEP-001 is raised after more than 7 days of silence by default, not at 7`() {
        PackCurrency.evaluate(reached, reached.plusDays(7), SilenceLimit.DEFAULT, null).raised shouldBe false
        val eight = PackCurrency.evaluate(reached, reached.plusDays(8), SilenceLimit.DEFAULT, null)
        eight.raised shouldBe true
        eight.daysSinceLastReached shouldBe 8
        eight.depCode shouldBe "DEP-001"
        SilenceLimit.ofPolicyValue(null) shouldBe SilenceLimit.DEFAULT
        SilenceLimit.DEFAULT.days shouldBe 7
    }

    @Test
    fun `INV-AC-2383 an air-gapped installation may set the limit up to 30 days and no further`() {
        val thirty = SilenceLimit.ofPolicyValue("30")
        thirty.days shouldBe 30
        PackCurrency.evaluate(reached, reached.plusDays(30), thirty, null).raised shouldBe false
        PackCurrency.evaluate(reached, reached.plusDays(31), thirty, null).raised shouldBe true
        // a value above 30, below 7 or malformed is never applied: the stricter default stands
        listOf("31", "6", "0", "-7", "7 ", " 7", "7.5", "1e1", "", "DAYS_30", "days_7").forEach {
            SilenceLimit.ofPolicyValue(it) shouldBe SilenceLimit.DEFAULT
        }
        SilenceLimit.ofPolicyValue("DAYS_7") shouldBe SilenceLimit.DEFAULT
    }

    @Test
    fun `INV-AC-2382 the days to the effective date of the unactivated pack count down and are never negative`() {
        val today = LocalDate.of(2026, 11, 10)
        PackCurrency.evaluate(today, today, SilenceLimit.DEFAULT, pack).daysToEffectiveDate shouldBe 21
        PackCurrency.evaluate(today, today.plusDays(20), SilenceLimit.DEFAULT, pack).daysToEffectiveDate shouldBe 1
        PackCurrency.evaluate(today, LocalDate.of(2026, 12, 5), SilenceLimit.DEFAULT, pack).daysToEffectiveDate shouldBe
            0
    }

    @Test
    fun `INV-AC-2382 INV-AC-2076 the condition clears when no unactivated pack is left and the feed is not silent`() {
        val raised = PackCurrency.evaluate(reached, reached, SilenceLimit.DEFAULT, pack)
        raised.raised shouldBe true
        val cleared = PackCurrency.evaluate(reached, reached, SilenceLimit.DEFAULT, null)
        cleared.raised shouldBe false
        PackCurrency.mustPublish(raised, cleared) shouldBe true
    }

    @Test
    fun `INV-AC-2382 the event is published only when the tuple differs from the last published tuple`() {
        val first = PackCurrency.evaluate(reached, reached, SilenceLimit.DEFAULT, pack)
        PackCurrency.mustPublish(null, first) shouldBe true
        PackCurrency.mustPublish(first, first.copy()) shouldBe false
        PackCurrency.mustPublish(first, first.copy(daysToEffectiveDate = 29)) shouldBe true
        PackCurrency.mustPublish(first, first.copy(packVersion = "2026.12 ")) shouldBe true
        // nothing raised and nothing published: no event
        val none = PackCurrency.evaluate(reached, reached, SilenceLimit.DEFAULT, null)
        PackCurrency.mustPublish(null, none) shouldBe false
    }

    @Test
    fun `INV-AC-2073 the engine range check refuses a newer minimum and any unreadable version`() {
        EngineCompatibility.isCompatible("1.0.0", "2.0.0") shouldBe true
        EngineCompatibility.isCompatible("2.0.0", "2.0.0") shouldBe true
        EngineCompatibility.isCompatible("2.0.1", "2.0.0") shouldBe false
        EngineCompatibility.isCompatible("3", "2.9.9") shouldBe false
        EngineCompatibility.isCompatible("2", "2.0.0") shouldBe true
        EngineCompatibility.isCompatible("10.0.0", "9.0.0") shouldBe false
        listOf("", "x", "1..2", "1.0.0-rc", " 1.0.0", "1.0.0 ", "-1.0").forEach {
            EngineCompatibility.isCompatible(it, "2.0.0") shouldBe false
            EngineCompatibility.isCompatible("1.0.0", it) shouldBe false
        }
    }
}
