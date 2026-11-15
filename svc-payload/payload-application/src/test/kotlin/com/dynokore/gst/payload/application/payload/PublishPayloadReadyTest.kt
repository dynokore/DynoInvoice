package com.dynokore.gst.payload.application.payload

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

class PublishPayloadReadyTest {
    private class Recording : PayloadReadyPublisher {
        val staged = ArrayList<PayloadReadyFacts>()

        override fun stage(facts: PayloadReadyFacts) {
            staged += facts
        }
    }

    private val bytes = "<Invoice/>".toByteArray()
    private val digest = digestOf(bytes)
    private val doc = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val port = Recording()
    private val useCase = PublishPayloadReady(port)

    private fun facts(
        payload: ByteArray = bytes,
        sha: String = digest,
        type: String = "380",
        spec: String = "1.0.0",
        aggregate: String? = "AGG-1",
    ) = PayloadReadyFacts("ent-1", doc, 2, type, spec, payload, sha, aggregate)

    private fun refused(f: PayloadReadyFacts) {
        assertThrows(IllegalArgumentException::class.java) { useCase.publish(f) }
        port.staged.size shouldBe 0
    }

    @Test
    fun `INV-AC-22857 true digest stages one fact set with every fact given`() {
        useCase.publish(facts())
        port.staged.size shouldBe 1
        val s = port.staged.single()
        s.payloadBytes.contentEquals(bytes) shouldBe true
        s.payloadSha256 shouldBe digest
        s.docUuid shouldBe doc
        s.version shouldBe 2
        s.entityRef shouldBe "ent-1"
        s.submissionType shouldBe "380"
        s.specificationVersion shouldBe "1.0.0"
        s.aggregateId shouldBe "AGG-1"
    }

    @Test
    fun `INV-AC-22857 digest of another content throws and stages nothing`() =
        refused(facts(sha = digestOf("x".toByteArray())))

    @Test
    fun `INV-AC-22857 upper-case digest is refused because the schema pattern is lower-case hex`() =
        refused(facts(sha = digest.uppercase()))

    @Test
    fun `INV-AC-22857 empty bytes throw and stage nothing`() =
        refused(facts(payload = ByteArray(0), sha = digestOf(ByteArray(0))))

    @Test
    fun `INV-AC-22857 blank submissionType throws and stages nothing`() = refused(facts(type = " "))

    @Test
    fun `INV-AC-22857 blank specificationVersion throws and stages nothing`() = refused(facts(spec = ""))

    @Test
    fun `INV-AC-22857 blank aggregateId throws and stages nothing`() = refused(facts(aggregate = " "))

    @Test
    fun `INV-AC-22857 absent aggregateId stays null and is not the string null`() {
        useCase.publish(facts(aggregate = null))
        port.staged.single().aggregateId shouldBe null
    }

    private fun digestOf(b: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))
}
