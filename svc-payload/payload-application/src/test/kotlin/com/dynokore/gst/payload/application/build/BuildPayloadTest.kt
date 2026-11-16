package com.dynokore.gst.payload.application.build

import com.dynokore.gst.payload.application.payload.PayloadReadyFacts
import com.dynokore.gst.payload.application.payload.PublishPayloadReady
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

class BuildPayloadTest {
    private val d1 = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val d2 = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val d3 = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val d4 = UUID.fromString("00000000-0000-0000-0000-000000000004")
    private val d5 = UUID.fromString("00000000-0000-0000-0000-000000000005")
    private val small = "<Invoice/>".toByteArray()
    private val big = ByteArray(10_485_761)

    private fun input(aggregate: String? = "AGG-2026-0001") = BuildInput("ENT-1", "{}", aggregate, null, null)

    private val ready = ArrayList<PayloadReadyFacts>()
    private val rejected = ArrayList<PayloadRejectedFacts>()
    private val seen = HashSet<Pair<UUID, Int>>()
    private var decisionText: String? = "TYPE_2"
    private val firstTimeCheck = true

    private val readerMap = mapOf(d1 to input(), d2 to input(), d3 to input(), d5 to input())

    private fun build(
        types: Map<UUID, String> = emptyMap(),
        projections: Map<UUID, Projection> =
            mapOf(
                d1 to Projection.Built(small),
                d2 to Projection.Built(big),
                d3 to Projection.Blocked("{\"finding\":\"BLOCK\"}"),
                d5 to Projection.Blocked("{\"finding\":\"BLOCK\"}", packVersion = "MDE-7"),
            ),
    ): BuildPayload {
        val byDoc = readerMap
        var current: UUID? = null
        return BuildPayload(
            inputs = { doc, _ ->
                current = doc
                byDoc[doc]
            },
            decisions = { _, _ -> types[current] ?: decisionText },
            projector = { _, _ -> projections.getValue(current!!) },
            families = { "SG-PINT-1.0" },
            ready = PublishPayloadReady { ready += it },
            rejected = { rejected += it },
            outcomes = { doc, v -> !firstTimeCheck || seen.add(doc to v) },
        )
    }

    private fun sha(b: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))

    @Test
    fun `INV-AC-22863 built version stages one payload ready`() {
        build().run(d1, 1)
        ready.size shouldBe 1
        rejected.size shouldBe 0
        val f = ready.single()
        f.submissionType shouldBe "2"
        f.aggregateId shouldBe "AGG-2026-0001"
        f.payloadBytes.contentEquals(small) shouldBe true
        f.payloadSha256 shouldBe sha(small)
        f.entityRef shouldBe "ENT-1"
        f.specificationVersion shouldBe "SG-PINT-1.0"
    }

    @Test
    fun `INV-AC-22863 rerun stages nothing`() {
        val uc = build()
        uc.run(d1, 1)
        uc.run(d1, 1)
        ready.size shouldBe 1
        rejected.size shouldBe 0
    }

    @Test
    fun `INV-AC-22863 oversized payload is rejected with PAY-010`() {
        build().run(d2, 1)
        ready.size shouldBe 0
        rejected.single().code shouldBe "PAY-010"
    }

    @Test
    fun `INV-AC-22863 blocked projection is rejected with its trace`() {
        build().run(d3, 1)
        ready.size shouldBe 0
        rejected.single().validationTrace shouldBe "{\"finding\":\"BLOCK\"}"
    }

    @Test
    fun `INV-AC-22863 missing input is not ready`() {
        assertThrows(BuildNotReady::class.java) { build().run(d4, 1) }
        ready.size shouldBe 0
        rejected.size shouldBe 0
    }

    @Test
    fun `INV-AC-22863 absent decision is not ready`() {
        decisionText = null
        assertThrows(BuildNotReady::class.java) { build().run(d1, 1) }
        ready.size shouldBe 0
    }

    @Test
    fun `INV-AC-22863 type is mapped to the payload spelling`() {
        val expected = mapOf("TYPE_1A" to "1A", "TYPE_1B" to "1B", "TYPE_2" to "2", "TYPE_3" to "3")
        expected.forEach { (decision, spelling) ->
            ready.clear()
            seen.clear()
            build(types = mapOf(d1 to decision)).run(d1, 1)
            ready.single().submissionType shouldBe spelling
        }
        assertThrows(IllegalStateException::class.java) { build(types = mapOf(d1 to "2")).run(d1, 2) }
    }

    @Test
    fun `INV-AC-23594 packVersions of a blocked projection`() {
        build().run(d5, 1)
        rejected.single().packVersions shouldBe listOf(PackVersionFact("MDE", "MDE-7"))
    }

    @Test
    fun `INV-AC-23594 no packVersions without a pack`() {
        build().run(d3, 1)
        rejected.single().packVersions shouldBe null
    }

    @Test
    fun `INV-AC-23594 PAY-010 has no packVersions`() {
        build(projections = mapOf(d2 to Projection.Built(big))).run(d2, 1)
        rejected.single().code shouldBe "PAY-010"
        rejected.single().packVersions shouldBe null
    }
}
