package com.dynokore.gst.reconciliation.application.workpaper

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest
import java.util.HexFormat

class SealWorkPaperTest {
    private class Fact(
        val tenant: String,
        val entityRef: String,
        val type: String,
        val reference: String,
        val hash: String,
    )

    private class Fakes {
        var doc: ByteArray? = null
        val stored = mutableListOf<Triple<Int, ByteArray, String>>()
        val facts = mutableListOf<Fact>()
        val order = mutableListOf<String>()
        var storeThrows = false
        val source = WorkPaperSource { _, _ -> doc?.let { SourceWorkPaper("R1", it) } }
        val store =
            object : SealedWorkPapers {
                override fun latest(
                    tenant: String,
                    runId: String,
                ): SealedRef? =
                    stored.lastOrNull()?.let {
                        SealedRef(it.third, sha(it.second), it.first)
                    }

                override fun put(
                    tenant: String,
                    entityRef: String,
                    runId: String,
                    sealVersion: Int,
                    bytes: ByteArray,
                ): String {
                    order += "store"
                    if (storeThrows) error("store down")
                    val ref = "ref-T1-R1-v$sealVersion"
                    stored += Triple(sealVersion, bytes, ref)
                    return ref
                }
            }
        val publisher =
            ArtefactRecordedPublisher { t, e, a, r, h ->
                order += "publish"
                facts += Fact(t, e, a, r, h)
            }
        val useCase = SealWorkPaper(source, store, publisher)
    }

    // Two builds of the same content: member order is fixed by the canonical form (sorted keys).
    private fun build(withAttestation: Boolean): ByteArray {
        val members = sortedMapOf<String, String>("runId" to "\"R1\"", "boxes" to "[]")
        if (withAttestation) members["attestation"] = "{\"by\":\"U1\"}"
        return members.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }.toByteArray()
    }

    companion object {
        fun sha(b: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))
    }

    @Test
    fun `INV-AC-24173 first run seals once and publishes one fact with type hash and the store reference`() {
        val f = Fakes().apply { doc = build(false) }
        f.useCase.execute("T1", "R1") shouldBe SealOutcome.SEALED
        f.stored.size shouldBe 1
        f.stored[0].second shouldBe build(false)
        f.facts.size shouldBe 1
        val fact = f.facts[0]
        fact.type shouldBe "RECONCILIATION_WORK_PAPER"
        fact.hash shouldBe sha(f.stored[0].second)
        fact.hash shouldMatch Regex("[0-9a-f]{64}")
        fact.reference shouldBe f.stored[0].third
        fact.tenant shouldBe "T1"
        fact.entityRef shouldBe "R1"
    }

    @Test
    fun `INV-AC-24173 the fact is published after the store call`() {
        val f = Fakes().apply { doc = build(false) }
        f.useCase.execute("T1", "R1")
        f.order shouldBe listOf("store", "publish")
    }

    @Test
    fun `INV-AC-24173 the same content again is ALREADY_SEALED and stores and publishes nothing`() {
        val f = Fakes().apply { doc = build(false) }
        f.useCase.execute("T1", "R1")
        f.useCase.execute("T1", "R1") shouldBe SealOutcome.ALREADY_SEALED
        f.stored.size shouldBe 1
        f.facts.size shouldBe 1
    }

    @Test
    fun `INV-AC-24173 an attested document is sealed again with another reference and hash`() {
        val f = Fakes().apply { doc = build(false) }
        f.useCase.execute("T1", "R1")
        f.doc = build(true)
        f.useCase.execute("T1", "R1") shouldBe SealOutcome.SEALED
        f.facts.size shouldBe 2
        (f.facts[0].reference == f.facts[1].reference) shouldBe false
        (f.facts[0].hash == f.facts[1].hash) shouldBe false
        f.facts[1].reference shouldBe "ref-T1-R1-v2"
    }

    @Test
    fun `INV-AC-24173 a run without a work-paper is NO_WORK_PAPER and publishes nothing`() {
        val f = Fakes()
        f.useCase.execute("T1", "R9") shouldBe SealOutcome.NO_WORK_PAPER
        f.stored.size shouldBe 0
        f.facts.size shouldBe 0
    }

    @Test
    fun `INV-AC-24173 when the store throws nothing is published and the exception propagates`() {
        val f =
            Fakes().apply {
                doc = build(false)
                storeThrows = true
            }
        assertThrows<IllegalStateException> { f.useCase.execute("T1", "R1") }
        f.facts.size shouldBe 0
    }

    @Test
    fun `INV-AC-24173 two seals of two builds of the same content store the same bytes in the same member order`() {
        val first = Fakes().apply { doc = build(true) }
        val second = Fakes().apply { doc = build(true) }
        first.useCase.execute("T1", "R1")
        second.useCase.execute("T1", "R1")
        String(first.stored[0].second) shouldBe """{"attestation":{"by":"U1"},"boxes":[],"runId":"R1"}"""
        String(second.stored[0].second) shouldBe String(first.stored[0].second)
        second.facts[0].hash shouldBe first.facts[0].hash
    }

    @Test
    fun `INV-AC-24183 a source work-paper with a blank entity reference is refused`() {
        assertThrows<IllegalArgumentException> { SourceWorkPaper("", ByteArray(0)) }
        assertThrows<IllegalArgumentException> { SourceWorkPaper("  ", ByteArray(0)) }
    }

    @Test
    fun `INV-AC-24183 the entity of the source work-paper not the run id goes to the store and the publisher`() {
        val f = Fakes().apply { doc = build(false) }
        val seen = mutableListOf<String>()
        val useCase =
            SealWorkPaper(
                WorkPaperSource { _, _ -> SourceWorkPaper("E1", build(false)) },
                object : SealedWorkPapers {
                    override fun latest(
                        tenant: String,
                        runId: String,
                    ): SealedRef? = null

                    override fun put(
                        tenant: String,
                        entityRef: String,
                        runId: String,
                        sealVersion: Int,
                        bytes: ByteArray,
                    ): String {
                        seen += "put:$entityRef"
                        return "ref"
                    }
                },
                ArtefactRecordedPublisher { _, e, _, _, _ -> seen += "publish:$e" },
            )
        useCase.execute("T1", "R1") shouldBe SealOutcome.SEALED
        seen shouldBe listOf("put:E1", "publish:E1")
        f.facts.size shouldBe 0
    }
}
