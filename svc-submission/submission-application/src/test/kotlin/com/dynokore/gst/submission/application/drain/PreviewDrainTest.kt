package com.dynokore.gst.submission.application.drain

import com.dynokore.gst.submission.domain.drain.Tier
import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PreviewDrainTest {
    private val amounts: Map<String, ListFacts?> =
        mapOf(
            "A" to DrainWorld.facts("100.00"),
            "B" to DrainWorld.facts("250.50"),
            "C" to DrainWorld.facts("0.50"),
            "X" to DrainWorld.facts("10.00"),
            "Y" to DrainWorld.facts("5.00"),
        )

    /** Person key for HUMAN and CONSENT_AGENT on E1; machine key for a MACHINE on E1; nothing else. */
    private val allowed: (String, PrincipalKind, String) -> Boolean = { key, kind, entity ->
        entity == "E1" &&
            when (kind) {
                PrincipalKind.MACHINE -> key == DrainActions.MACHINE
                else -> key == DrainActions.PERSON
            }
    }

    private fun world(
        facts: Map<String, ListFacts?> = amounts,
        unavailable: Boolean = false,
    ) = DrainWorld(
        mutableListOf(
            DrainWorld.queued("A", "E1", "2026-Q1", 1),
            DrainWorld.queued("B", "E1", "2026-Q1", 2),
            DrainWorld.queued("C", "E1", "2026-Q1", 3),
            DrainWorld.queued("X", "E2", "2026-Q2", 4),
            DrainWorld.queued("Y", "E1", "2026-Q2", 5),
        ),
        mapOf(
            ("E1" to "2026-Q1") to date("2026-12-15"),
            ("E2" to "2026-Q2") to date("2026-12-01"),
            ("E1" to "2026-Q2") to date("2027-01-15"),
        ),
        mapOf("E1" to SafetyMarginAnswer.Known(days(7)), "E2" to SafetyMarginAnswer.Known(days(7))),
        allowedKeys = allowed,
        unavailableDecision = unavailable,
        facts = facts,
    )

    @Test
    fun `INV-AC-20846 the HUMAN preview counts the set a drain attempts and sums to a scale 2 total`() {
        val w = world()
        val p = w.preview().run(w.request(HUMAN), w.deadline).shouldBeInstanceOf<PreviewResult.Previewed>()
        p.documentCount shouldBe 5
        p.totalAmount shouldBe BigDecimal("366.00")
        p.totalAmount.scale() shouldBe 2
        w.allWrites shouldBe emptyList()
        w.despatched shouldBe emptyList()
        w.backlog.size shouldBe 5

        val d = world()
        val drained = d.drain().run(d.request(HUMAN), d.deadline).shouldBeInstanceOf<DrainResult.Drained>()
        drained.drainedCount shouldBe p.documentCount
        drained.drainedByTier shouldBe mapOf(Tier.WINDOW_OPEN to 1, Tier.DRAINED_PERIOD to 3, Tier.ROUTINE to 1)
    }

    @Test
    fun `INV-AC-20846 a CONSENT_AGENT is allowed through the person key and its preview equals the HUMAN answer`() {
        val h = world().let { it.preview().run(it.request(HUMAN), it.deadline) }
        val w = world()
        w.preview().run(w.request(AGENT), w.deadline) shouldBe h
        w.decisions shouldBe listOf(Triple(DrainActions.PERSON, PrincipalKind.CONSENT_AGENT, "E1"))
        val d = world()
        d.drain().run(d.request(AGENT), d.deadline).shouldBeInstanceOf<DrainResult.Drained>()
        d.decisions.first() shouldBe Triple(DrainActions.PERSON, PrincipalKind.CONSENT_AGENT, "E1")
    }

    @Test
    fun `INV-AC-20846 a MACHINE principal is Forbidden for the preview and allowed for the drain of E1 only`() {
        val w = world()
        w.preview().run(w.request(machineFor("E1")), w.deadline).shouldBeInstanceOf<PreviewResult.Forbidden>()
        w.decisions.none { it.first == DrainActions.MACHINE } shouldBe true

        val d1 = world()
        d1.drain().run(d1.request(machineFor("E1")), d1.deadline).shouldBeInstanceOf<DrainResult.Drained>()
        d1.decisions.first() shouldBe Triple(DrainActions.MACHINE, PrincipalKind.MACHINE, "E1")

        val d2 = world()
        d2
            .drain()
            .run(d2.request(machineFor("E1"), entity = "E2"), d2.deadline)
            .shouldBeInstanceOf<DrainResult.Forbidden>()
        d2.decisions shouldBe listOf(Triple(DrainActions.MACHINE, PrincipalKind.MACHINE, "E2"))
        d2.allWrites shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20846 every decision carries one of the two keys and an AUTHORITY_UNAVAILABLE decision is Forbidden`() {
        val w = world(unavailable = true)
        w
            .preview()
            .run(w.request(HUMAN), w.deadline)
            .shouldBeInstanceOf<PreviewResult.Forbidden>()
            .reason shouldBe DecisionReason.AUTHORITY_UNAVAILABLE
        w.drain().run(w.request(HUMAN), w.deadline).shouldBeInstanceOf<DrainResult.Forbidden>()
        w.decisions.map { it.first }.toSet() shouldBe setOf(DrainActions.PERSON)
        w.allWrites shouldBe emptyList()
        w.despatched shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20846 a null amount makes the preview unavailable with no count and no partial total`() {
        val w = world(facts = amounts + ("C" to null))
        w.preview().run(w.request(HUMAN), w.deadline).shouldBeInstanceOf<PreviewResult.Unavailable>()
    }
}
