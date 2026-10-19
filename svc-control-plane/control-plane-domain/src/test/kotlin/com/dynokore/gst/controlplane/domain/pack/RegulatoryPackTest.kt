package com.dynokore.gst.controlplane.domain.pack

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant

class RegulatoryPackTest {
    private val alice = ActorId("alice.author@operator.example")
    private val bob = ActorId("bob.approver@operator.example")
    private val at = Instant.parse("2026-10-12T08:30:00Z")

    private fun draft() = RegulatoryPack.draft(PackType("SCOPE"), PackVersion("2026.3"), listOf("IRAS e-Tax Guide 2026-08"), alice)

    @Test
    fun `INV-AC-2591 signPack by an author is refused and the version stays unsigned`() {
        val edited = draft().authoredBy(bob) // bob also edited, and holds both roles
        val out = edited.sign(bob, "KEY-REG-01", MinEngineVersion("2026.10.0"), false, at)
        out.shouldBeInstanceOf<SignOutcome.SeparationOfDutyRefused>()
        out.pack.status shouldBe PackStatus.DRAFT
        out.pack.signer shouldBe null
        PublicationGate.check(out.pack, emptyList(), ContentHash("0".repeat(64))) shouldBe
            PublicationRefusal.NotSigned(out.pack.ref)
    }

    @Test
    fun `INV-AC-2591 the original author cannot sign either`() {
        draft().sign(alice, "KEY-REG-01", null, false, at).shouldBeInstanceOf<SignOutcome.SeparationOfDutyRefused>()
    }

    @Test
    fun `INV-AC-2595 a person who did not edit the version signs it, signer recorded and author kept`() {
        val out = draft().sign(bob, "KEY-REG-01", MinEngineVersion("2026.10.0"), false, at)
        out.shouldBeInstanceOf<SignOutcome.Signed>()
        out.pack.status shouldBe PackStatus.SIGNED
        out.pack.signer shouldBe bob
        out.pack.authors shouldContainExactlyInAnyOrder setOf(alice)
    }

    @Test
    fun `a repeat of signPack on a signed version is a no-op`() {
        val signed = (draft().sign(bob, "KEY-REG-01", null, false, at) as SignOutcome.Signed).pack
        signed.sign(ActorId("carol"), "KEY-REG-02", null, true, at.plusSeconds(1)) shouldBe SignOutcome.AlreadySigned(signed)
    }

    @Test
    fun `a call after signing adds no author`() {
        val signed = (draft().sign(bob, "KEY-REG-01", null, false, at) as SignOutcome.Signed).pack
        signed.authoredBy(ActorId("carol")).authors shouldBe setOf(alice)
    }
}
