package com.dynokore.gst.evidence.domain.sourceref

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

class SourceReferenceSampleTest {
    private val refs =
        (1..5).map {
            SourceReference(UUID.fromString("00000000-0000-0000-0000-00000000000$it"), it, "s3://docs/r$it")
        }

    private fun key(
        period: String,
        r: SourceReference,
    ): String {
        val bytes = (period + "\n" + r.docUuid + "\n" + r.version).toByteArray(Charsets.UTF_8)
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private fun expected(
        period: String,
        n: Int,
    ) = refs.sortedBy { key(period, it) }.take(n)

    @Test
    fun `INV-AC-16009 five references and size 3 select the three smallest keys in order`() {
        SourceReferenceSampler.select(refs, "2026-Q1", SampleSize(3)) shouldBe expected("2026-Q1", 3)
    }

    @Test
    fun `INV-AC-16009 same call twice and shuffled input give equal lists`() {
        val first = SourceReferenceSampler.select(refs, "2026-Q1", SampleSize(3))
        SourceReferenceSampler.select(refs, "2026-Q1", SampleSize(3)) shouldBe first
        SourceReferenceSampler.select(refs.reversed(), "2026-Q1", SampleSize(3)) shouldBe first
        SourceReferenceSampler.select(refs.shuffled(java.util.Random(7)), "2026-Q1", SampleSize(3)) shouldBe first
    }

    @Test
    fun `INV-AC-16009 each period is ordered by its own keys`() {
        SourceReferenceSampler.select(refs, "2026-Q1", SampleSize(3)) shouldBe expected("2026-Q1", 3)
        SourceReferenceSampler.select(refs, "2026-Q2", SampleSize(3)) shouldBe expected("2026-Q2", 3)
    }

    @Test
    fun `INV-AC-16009 size 9 over five entries returns all five`() {
        SourceReferenceSampler.select(refs, "2026-Q1", SampleSize(9)) shouldBe expected("2026-Q1", 5)
    }

    @Test
    fun `INV-AC-16009 empty input returns empty list`() {
        SourceReferenceSampler.select(emptyList(), "2026-Q1", SampleSize(3)) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-16009 sample size below 1 is refused`() {
        shouldThrow<IllegalArgumentException> { SampleSize(0) }
        shouldThrow<IllegalArgumentException> { SampleSize(-1) }
    }

    @Test
    fun `INV-AC-16009 blank period key is refused in select and report`() {
        listOf("", " ", " \t\n").forEach {
            shouldThrow<IllegalArgumentException> { SourceReferenceSampler.select(refs, it, SampleSize(3)) }
            shouldThrow<IllegalArgumentException> { SourceReferenceSampler.report(it, emptyList(), emptyMap()) }
        }
    }

    @Test
    fun `INV-AC-16009 same docUuid and version is refused as duplicate`() {
        val dup = refs[0].copy(reference = "s3://docs/other")
        shouldThrow<IllegalArgumentException> {
            SourceReferenceSampler.select(listOf(refs[0], dup), "2026-Q1", SampleSize(3))
        }.message!! shouldContain "duplicate"
    }

    @Test
    fun `INV-AC-16009 source reference limits`() {
        val id = UUID.randomUUID()
        shouldThrow<IllegalArgumentException> { SourceReference(id, 0, "x") }
        shouldThrow<IllegalArgumentException> { SourceReference(id, 1, " ") }
        shouldThrow<IllegalArgumentException> { SourceReference(id, 1, "x".repeat(513)) }
        SourceReference(id, 1, "x".repeat(512)).reference.length shouldBe 512
    }

    @Test
    fun `INV-AC-16009 report counts and gives an advisory finding for an unresolved entry`() {
        val sample = refs.take(3)
        val report =
            SourceReferenceSampler.report(
                "2026-Q1",
                sample,
                mapOf(
                    sample[0] to ResolveOutcome.RESOLVED,
                    sample[1] to ResolveOutcome.UNRESOLVED,
                    sample[2] to ResolveOutcome.NOT_VERIFIED,
                ),
            )
        report.checked shouldBe 3
        report.resolved shouldBe 1
        report.unresolved shouldBe listOf(sample[1])
        report.notVerified shouldBe listOf(sample[2])
        report.effect shouldBe ReadinessEffect.ADVISORY_FINDING
    }

    @Test
    fun `INV-AC-16009 all resolved gives no effect`() {
        val sample = refs.take(3)
        val report = SourceReferenceSampler.report("2026-Q1", sample, sample.associateWith { ResolveOutcome.RESOLVED })
        report.resolved shouldBe 3
        report.effect shouldBe ReadinessEffect.NONE
    }

    @Test
    fun `INV-AC-16009 only not verified gives no effect and lists the entry`() {
        val sample = refs.take(1)
        val report = SourceReferenceSampler.report("2026-Q1", sample, mapOf(sample[0] to ResolveOutcome.NOT_VERIFIED))
        report.effect shouldBe ReadinessEffect.NONE
        report.notVerified shouldBe sample
        report.unresolved shouldBe emptyList()
    }

    @Test
    fun `INV-AC-16009 missing and extra outcomes are refused`() {
        val sample = refs.take(2)
        shouldThrow<IllegalArgumentException> {
            SourceReferenceSampler.report("2026-Q1", sample, mapOf(sample[0] to ResolveOutcome.RESOLVED))
        }.message!! shouldContain "outcome"
        shouldThrow<IllegalArgumentException> {
            SourceReferenceSampler.report(
                "2026-Q1",
                sample,
                sample.associateWith { ResolveOutcome.RESOLVED } + (refs[4] to ResolveOutcome.RESOLVED),
            )
        }.message!! shouldContain "outcome"
    }

    @Test
    fun `INV-AC-16009 readiness effect has no blocking member`() {
        ReadinessEffect.entries.map { it.name } shouldBe listOf("NONE", "ADVISORY_FINDING")
    }
}
