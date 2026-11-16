package com.dynokore.gst.rules.domain.pack

import com.dynokore.gst.rules.domain.pack.PackCorrectionResult.Refused
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PackCorrectionTest {
    private val badDate = LocalDate.of(2026, 3, 1)
    private val bad = BadPack("GST_RULES", "2026.3", badDate, PackStatus.ARMED)
    private val earlier = EarlierPack("2026.2", "content-2026.2")

    @Test
    fun `INV-AC-24013 the spec has the bad date, the label plus -rollback and the earlier content`() {
        PackCorrection.supersede(bad, earlier, badDate) shouldBe
            PackCorrectionResult.Superseding(
                SupersedingSpec("GST_RULES", "2026.3-rollback", badDate, "content-2026.2", PackStatus.ARMED),
            )
    }

    @Test
    fun `INV-AC-24013 the clock date, an earlier and a later date are refused as DateMismatch`() {
        val today = LocalDate.of(2026, 11, 17)
        listOf(today, badDate.plusDays(1), badDate.minusDays(1)).forEach {
            PackCorrection.supersede(bad, earlier, it) shouldBe Refused.DateMismatch(it, badDate)
        }
    }

    @Test
    fun `INV-AC-24013 a label of 17 characters is refused and 16 is accepted`() {
        val sixteen = bad.copy(version = "1234567") // 7 + 9 = 16
        (PackCorrection.supersede(sixteen, earlier, badDate) is PackCorrectionResult.Superseding) shouldBe true
        val seventeen = bad.copy(version = "12345678") // 8 + 9 = 17
        PackCorrection.supersede(seventeen, earlier, badDate) shouldBe Refused.LabelTooLong("12345678-rollback")
    }

    @Test
    fun `INV-AC-24013 no earlier pack is refused`() {
        PackCorrection.supersede(bad, null, badDate) shouldBe Refused.NoEarlierPack
    }

    @Test
    fun `INV-AC-24013 a bad pack that is not ARMED is refused for every other status`() {
        PackStatus.entries.filter { it != PackStatus.ARMED }.forEach {
            PackCorrection.supersede(bad.copy(status = it), earlier, badDate) shouldBe Refused.NotArmed(it)
        }
    }

    @Test
    fun `INV-AC-24013 the spec keeps the type and effective date of the bad pack`() {
        val spec = (PackCorrection.supersede(bad, earlier, badDate) as PackCorrectionResult.Superseding).spec
        spec.packType shouldBe bad.packType
        spec.effectiveFrom shouldBe bad.effectiveFrom
        spec.version shouldBe bad.version + "-rollback"
    }

    @Test
    fun `INV-AC-24013 the spec status is ARMED and not DRAFT, APPROVED or STAGED`() {
        val spec = (PackCorrection.supersede(bad, earlier, badDate) as PackCorrectionResult.Superseding).spec
        spec.status shouldBe PackStatus.ARMED
        listOf(PackStatus.DRAFT, PackStatus.APPROVED, PackStatus.STAGED).forEach { (spec.status == it) shouldBe false }
    }
}
