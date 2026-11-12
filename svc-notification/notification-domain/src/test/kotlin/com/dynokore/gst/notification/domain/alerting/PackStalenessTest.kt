package com.dynokore.gst.notification.domain.alerting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PackStalenessTest {
    private val tiers = listOf(PolicyTier(1, 30), PolicyTier(2, 14), PolicyTier(3, 7), PolicyTier(4, null))

    private fun raise(
        days: Int?,
        t: List<PolicyTier> = tiers,
    ) = PackStaleness.decide(PackCurrency("DEP-001", PackStatus.RAISED, days), t)

    private fun draft(tier: Int) =
        PackDecision.Raise(PackAlertDraft(AlertClass.PACK_STALENESS, "PACK_NOT_ACTIVATED", tier, TENANT_WIDE_ENTITY_REF))

    @Test
    fun `INV-AC-20908 days 40 30 20 and absent give tier 1`() {
        raise(40) shouldBe draft(1)
        raise(30) shouldBe draft(1)
        raise(20) shouldBe draft(1)
        raise(null) shouldBe draft(1)
    }

    @Test
    fun `INV-AC-20908 days 14 and 8 give tier 2`() {
        raise(14) shouldBe draft(2)
        raise(8) shouldBe draft(2)
    }

    @Test
    fun `INV-AC-20908 days 7 and 0 give tier 3 and never tier 4`() {
        raise(7) shouldBe draft(3)
        raise(0) shouldBe draft(3)
    }

    @Test
    fun `INV-AC-20908 draft carries class trigger and tenant-wide reference`() {
        val d = (raise(14) as PackDecision.Raise).draft
        d.alertClass shouldBe AlertClass.PACK_STALENESS
        d.trigger shouldBe "PACK_NOT_ACTIVATED"
        d.entityRef shouldBe TENANT_WIDE_ENTITY_REF
    }

    @Test
    fun `INV-AC-20908 with no tiers every raise gives tier 1`() {
        raise(40, emptyList()) shouldBe draft(1)
        raise(0, emptyList()) shouldBe draft(1)
        raise(null, emptyList()) shouldBe draft(1)
    }

    @Test
    fun `INV-AC-20908 DEP-001 CLEARED resolves`() {
        PackStaleness.decide(PackCurrency("DEP-001", PackStatus.CLEARED, null), tiers) shouldBe PackDecision.Resolve
    }

    @Test
    fun `INV-AC-20908 DEP-002 RAISED and CLEARED are ignored`() {
        PackStaleness.decide(PackCurrency("DEP-002", PackStatus.RAISED, 14), tiers) shouldBe PackDecision.Ignore
        PackStaleness.decide(PackCurrency("DEP-002", PackStatus.CLEARED, null), tiers) shouldBe PackDecision.Ignore
    }

    @Test
    fun `INV-AC-20908 an invalid status is refused`() {
        assertThrows<IllegalArgumentException> { PackStatus.valueOf("RAISE") }
        assertThrows<IllegalArgumentException> { PackStatus.valueOf("raised") }
    }

    @Test
    fun `INV-AC-20908 a blank depCode and a negative days value are refused`() {
        assertThrows<IllegalArgumentException> { PackCurrency("", PackStatus.RAISED, 1) }
        assertThrows<IllegalArgumentException> { PackCurrency("  ", PackStatus.RAISED, 1) }
        assertThrows<IllegalArgumentException> { PackCurrency("DEP-001", PackStatus.RAISED, -1) }
    }
}
