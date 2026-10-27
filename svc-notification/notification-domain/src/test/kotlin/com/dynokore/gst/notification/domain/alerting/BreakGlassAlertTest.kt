package com.dynokore.gst.notification.domain.alerting

import com.dynokore.gst.notification.domain.alerting.BreakGlassDecision.Raise
import com.dynokore.gst.notification.domain.alerting.BreakGlassDecision.Withhold
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class BreakGlassAlertTest {
    private fun event(
        accCode: String = "ACC-002",
        scope: BreakGlassScope = BreakGlassScope.TENANT,
        action: BreakGlassAction = BreakGlassAction.UNLOCK,
        unlockRef: String? = "BG-0003",
    ) = BreakGlassAlertRaised(accCode, scope, action, unlockRef)

    private val two = listOf("security.officer:jane.tan", "security.officer:ravi.k")

    @Test
    fun `INV-AC-1934 unlock event raises one SECURITY alert to both holders with no tier`() {
        BreakGlassAlert.decide(event(), two) shouldBe
            Raise(
                BreakGlassAlertDraft(
                    alertClass = "SECURITY",
                    trigger = "BREAK_GLASS_USED",
                    entityRef = "00000000-0000-0000-0000-000000000000",
                    recipients = two,
                    tiers = emptyList(),
                ),
            )
    }

    @Test
    fun `INV-AC-2384 three recipients give exactly one alert listing all three and no tier`() {
        val three = listOf("r1", "r2", "r3")
        val d = BreakGlassAlert.decide(event(), three)
        (d is Raise) shouldBe true
        (d as Raise).draft.recipients shouldBe three
        d.draft.tiers shouldBe emptyList()
    }

    @Test
    fun `no usable recipient is withheld`() {
        BreakGlassAlert.decide(event(), emptyList()) shouldBe Withhold(BreakGlassWithheld.NO_RECIPIENT)
        BreakGlassAlert.decide(event(), listOf("  ")) shouldBe Withhold(BreakGlassWithheld.NO_RECIPIENT)
    }

    @Test
    fun `unsupported code is withheld and wins over no recipient`() {
        BreakGlassAlert.decide(event(accCode = "ACC-003"), two) shouldBe Withhold(BreakGlassWithheld.UNSUPPORTED_CODE)
        BreakGlassAlert.decide(event(accCode = "ACC-003"), emptyList()) shouldBe
            Withhold(BreakGlassWithheld.UNSUPPORTED_CODE)
    }

    @Test
    fun `recipients are distinct case-sensitive and ordered`() {
        val d = BreakGlassAlert.decide(event(), listOf("a", "a", "A")) as Raise
        d.draft.recipients shouldBe listOf("a", "A")
    }

    @Test
    fun `installation scope uses the tenant-wide entity ref`() {
        val d =
            BreakGlassAlert.decide(
                event(scope = BreakGlassScope.INSTALLATION, action = BreakGlassAction.REPLAY),
                two,
            ) as Raise
        d.draft.alertClass shouldBe "SECURITY"
        d.draft.trigger shouldBe "BREAK_GLASS_USED"
        d.draft.entityRef shouldBe TENANT_WIDE_ENTITY_REF
        d.draft.entityRef shouldNotBe "ffffffff-ffff-ffff-ffff-ffffffffffff"
    }

    @Test
    fun `equal input gives equal output`() {
        BreakGlassAlert.decide(event(), two) shouldBe BreakGlassAlert.decide(event(), two)
    }

    @Test
    fun `constructor refuses a bad code and a bad unlock ref`() {
        shouldThrow<IllegalArgumentException> { event(accCode = "ACC-2") }
        shouldThrow<IllegalArgumentException> { event(accCode = "ACC-0022") }
        shouldThrow<IllegalArgumentException> { event(accCode = "ACC-002\n") }
        shouldThrow<IllegalArgumentException> { event(unlockRef = "u".repeat(65)) }
        shouldThrow<IllegalArgumentException> { event(unlockRef = " ") }
        shouldThrow<IllegalArgumentException> { event(unlockRef = "") }
        event(unlockRef = "u".repeat(64)).unlockRef!!.length shouldBe 64
        event(unlockRef = null).unlockRef shouldBe null
    }

    @Test
    fun `fromKey is exact`() {
        listOf("unlock", "UNLOCK ", "UNLOCKED", "").forEach {
            shouldThrow<IllegalArgumentException> { BreakGlassAction.fromKey(it) }
        }
        shouldThrow<IllegalArgumentException> { BreakGlassScope.fromKey("tenant") }
        BreakGlassAction.entries.forEach { BreakGlassAction.fromKey(it.name) shouldBe it }
        BreakGlassScope.entries.forEach { BreakGlassScope.fromKey(it.name) shouldBe it }
    }

    @Test
    fun `enum member sets and constants are fixed`() {
        BreakGlassAction.entries.map { it.name }.toSet() shouldBe
            setOf("UNLOCK", "VIEW", "READINESS", "DRAIN", "REPLAY")
        BreakGlassScope.entries.map { it.name }.toSet() shouldBe setOf("TENANT", "INSTALLATION")
        BreakGlassWithheld.entries.map { it.name }.toSet() shouldBe setOf("UNSUPPORTED_CODE", "NO_RECIPIENT")
        ACC_BREAK_GLASS shouldBe "ACC-002"
        BREAK_GLASS_TEMPLATE shouldBe "BREAK_GLASS_ALERT"
    }

    @Test
    fun `message holds exactly four properties and no credential`() {
        BreakGlassMessageParameters::class.java.declaredFields
            .filter {
                !java.lang.reflect.Modifier
                    .isStatic(it.modifiers)
            }.map { it.name }
            .toSet() shouldBe
            setOf("template", "scope", "action", "unlockRef")
        BreakGlassMessage.of(event()) shouldBe
            BreakGlassMessageParameters("BREAK_GLASS_ALERT", BreakGlassScope.TENANT, BreakGlassAction.UNLOCK, "BG-0003")
    }
}
