package com.dynokore.gst.submission.application.drain

import com.dynokore.gst.submission.domain.drain.Tier
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant

class DrainResidualTest {
    private val at = Instant.parse("2026-11-20T00:00:00Z")

    private fun held(code: String? = "MDM-001") = ResidualCandidate("d1", 1, ResidualKind.PROVISIONAL, code, at)

    private fun dead(
        doc: String,
        version: Int,
        code: String?,
    ) = ResidualCandidate(doc, version, ResidualKind.DEAD_LETTERED, code, at)

    private fun rows(
        d2Code: String? = "PAY-014",
        withD3: Boolean = false,
    ) = listOf(held(), held(null).copy(docUuid = "d1b"), dead("d2", 2, d2Code), dead("d4", 1, "PAY-014")) +
        (if (withD3) listOf(dead("d3", 1, null)) else emptyList())

    private fun world(
        today: String,
        rows: List<ResidualCandidate> = rows(),
        determined: Boolean = true,
    ) = DrainWorld(
        mutableListOf(DrainWorld.queued("d4", "E1", "2026-Q1", 1)),
        mapOf(("E1" to "2026-Q1") to date("2026-12-15")),
        mapOf("E1" to SafetyMarginAnswer.Known(days(7))),
        before = today,
        residualRows = rows,
        determined = determined,
    )

    private fun items(tier: Tier) =
        listOf(ResidualItem("d1", 1, tier, "MDM-001"), ResidualItem("d2", 2, tier, "PAY-014"))

    @Test
    fun `INV-AC-20845 R1 reports the stored codes after the despatch and records the check of the call`() {
        val w = world("2026-11-28")
        val r = w.drain().run(w.request(HUMAN), w.deadline).shouldBeInstanceOf<DrainResult.Drained>()
        w.despatched shouldBe listOf("d4")
        r.drainedByTier shouldBe mapOf(Tier.WINDOW_OPEN to 0, Tier.DRAINED_PERIOD to 1, Tier.ROUTINE to 0)
        r.residualItems shouldBe items(Tier.DRAINED_PERIOD)
        r.remaining shouldBe listOf(TierBlocked(Tier.DRAINED_PERIOD, 2))
        w.residualCalledAfter shouldBe 1
        r.checkId shouldBe "CHK-1"
        w.stored.single().let {
            it.entityRef shouldBe "E1"
            it.periodRef shouldBe "2026-Q1"
            it.approver shouldBe HUMAN.id
        }
    }

    @Test
    fun `INV-AC-20845 R2 on the window opening date the residual items are tier 1`() {
        val w = world("2026-12-08")
        val r = w.drain().run(w.request(HUMAN), w.deadline).shouldBeInstanceOf<DrainResult.Drained>()
        r.residualItems shouldBe items(Tier.WINDOW_OPEN)
        r.remaining shouldBe listOf(TierBlocked(Tier.WINDOW_OPEN, 2))
        r.drainedByTier[Tier.WINDOW_OPEN] shouldBe 1
    }

    @Test
    fun `INV-AC-20845 R3 an undetermined readiness leaves the check id absent`() {
        val w = world("2026-11-28", determined = false)
        val r = w.drain().run(w.request(HUMAN), w.deadline).shouldBeInstanceOf<DrainResult.Drained>()
        r.checkId shouldBe null
        r.residualItems shouldBe items(Tier.DRAINED_PERIOD)
        w.stored shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20845 R4 a dead letter with no stored code makes the whole answer Unavailable`() {
        val w = world("2026-11-28", rows(withD3 = true))
        w.drain().run(w.request(HUMAN), w.deadline).shouldBeInstanceOf<DrainResult.Unavailable>()
        w.despatched shouldBe listOf("d4")
        w.stored shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20845 R5 a stored code outside the pattern makes the whole answer Unavailable`() {
        val w = world("2026-11-28", rows(d2Code = "pay-014"))
        w.drain().run(w.request(HUMAN), w.deadline).shouldBeInstanceOf<DrainResult.Unavailable>()
        w.despatched shouldBe listOf("d4")
    }
}
