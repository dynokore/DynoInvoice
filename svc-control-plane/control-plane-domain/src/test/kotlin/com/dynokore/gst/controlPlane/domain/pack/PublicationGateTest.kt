package com.dynokore.gst.controlPlane.domain.pack

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class PublicationGateTest {
    private val hash = ContentHash("a".repeat(64))
    private val other = ContentHash("b".repeat(64))
    private val signed =
        (
            RegulatoryPack
                .draft(PackType("SCOPE"), PackVersion("2026.3"), listOf("c"), ActorId("alice"))
                .sign(ActorId("bob"), "KEY", MinEngineVersion("2026.10.0"), false, Instant.EPOCH) as SignOutcome.Signed
        ).pack
    private val ref = signed.ref

    @Test
    fun `no record refuses`() {
        PublicationGate.check(signed, emptyList(), hash) shouldBe PublicationRefusal.NoStagingRecord(ref)
    }

    @Test
    fun `a failed record refuses`() {
        PublicationGate.check(signed, listOf(StagingEvidence(ref, StagingResult.FAILED, hash)), hash) shouldBe
            PublicationRefusal.StagingNotPassed(ref, listOf(StagingResult.FAILED))
    }

    @Test
    fun `a record of another version refuses as no record`() {
        val foreign = StagingEvidence(PackRef("PACK-SCOPE-2026.4"), StagingResult.PASSED, hash)
        PublicationGate.check(signed, listOf(foreign), hash) shouldBe PublicationRefusal.NoStagingRecord(ref)
    }

    @Test
    fun `a passed record over other content refuses with both hashes`() {
        PublicationGate.check(signed, listOf(StagingEvidence(ref, StagingResult.PASSED, other)), hash) shouldBe
            PublicationRefusal.StagingHashMismatch(ref, other, hash)
    }

    @Test
    fun `a passed record over the same content opens the gate`() {
        PublicationGate.check(signed, listOf(StagingEvidence(ref, StagingResult.PASSED, hash)), hash) shouldBe null
    }

    @Test
    fun `INV-AC-2078 a signed version without a minimum engine version is refused`() {
        val noEngine =
            (
                RegulatoryPack
                    .draft(PackType("SCOPE"), PackVersion("2026.5"), listOf("c"), ActorId("alice"))
                    .sign(ActorId("bob"), "KEY", null, false, Instant.EPOCH) as SignOutcome.Signed
            ).pack
        PublicationGate.check(noEngine, listOf(StagingEvidence(noEngine.ref, StagingResult.PASSED, hash)), hash) shouldBe
            PublicationRefusal.MinEngineVersionMissing(noEngine.ref)
    }
}
