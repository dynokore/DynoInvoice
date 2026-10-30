package com.dynokore.gst.evidence.domain.payload

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

class TransmittedPayloadSealTest {
    private val doc = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val at = Instant.parse("2026-10-30T01:02:03Z")
    private val xml = "<Invoice>x</Invoice>".toByteArray(Charsets.UTF_8)

    private fun sha(b: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))

    private fun held(
        b: ByteArray = xml,
        version: Int = 1,
        digest: String = sha(b),
        uuid: UUID = doc,
    ) = HeldPayload(uuid, version, b, digest)

    private fun despatch(
        version: Int = 1,
        digest: String = sha(xml),
        uuid: UUID = doc,
    ) = Despatch(uuid, version, 1, digest, at)

    private fun flip(d: String) = (if (d[0] == '0') "1" else "0") + d.substring(1)

    private fun sealOf(d: SealDecision) = d.shouldBeInstanceOf<SealDecision.Seal>().payload

    @Test
    fun `INV-AC-13385 matching despatch seals the held bytes and the content parses back`() {
        val json = sealOf(TransmittedPayloadSeal.decide(held(), despatch())).contentJson().toString(Charsets.UTF_8)
        val expected =
            "{\"docUuid\":\"$doc\",\"version\":1,\"attemptNumber\":1,\"payloadSha256\":\"${sha(xml)}\"," +
                "\"despatchedAt\":\"2026-10-30T01:02:03Z\"," +
                "\"payloadBase64\":\"${Base64.getEncoder().encodeToString(xml)}\"}"
        json shouldBe expected
        val keys = Regex("\"([A-Za-z0-9]+)\":").findAll(json).map { it.groupValues[1] }.toList()
        keys shouldBe listOf("docUuid", "version", "attemptNumber", "payloadSha256", "despatchedAt", "payloadBase64")
        val b64 = Regex("\"payloadBase64\":\"([^\"]*)\"").find(json)!!.groupValues[1]
        Base64.getDecoder().decode(b64).toList() shouldBe xml.toList()
    }

    @Test
    fun `INV-AC-13385 bytes that are not valid UTF-8 are sealed and decode back byte-identical`() {
        val raw = byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte())
        val d = despatch(digest = sha(raw))
        val sealed = sealOf(TransmittedPayloadSeal.decide(held(raw), d))
        val json = sealed.contentJson().toString(Charsets.UTF_8)
        val b64 = Regex("\"payloadBase64\":\"([^\"]*)\"").find(json)!!.groupValues[1]
        Base64.getDecoder().decode(b64).toList() shouldBe raw.toList()
        sealed.payloadBytes().toList() shouldBe raw.toList()
    }

    @Test
    fun `INV-AC-13385 a second version seals its own bytes and the first seal is unchanged`() {
        val first = sealOf(TransmittedPayloadSeal.decide(held(), despatch()))
        val v2 = "<Invoice>y</Invoice>".toByteArray(Charsets.UTF_8)
        val decision = TransmittedPayloadSeal.decide(held(v2, version = 2), despatch(version = 2, digest = sha(v2)))
        val second = sealOf(decision)
        second.payloadBytes().toList() shouldBe v2.toList()
        second.version shouldBe 2
        first.payloadBytes().toList() shouldBe xml.toList()
        first.version shouldBe 1
    }

    @Test
    fun `INV-AC-13385 a despatch digest one hex digit off is refused`() {
        TransmittedPayloadSeal.decide(held(), despatch(digest = flip(sha(xml)))) shouldBe
            SealDecision.Refused(SealRefusal.DESPATCHED_DIGEST_MISMATCH)
    }

    @Test
    fun `INV-AC-13385 no held payload awaits`() {
        TransmittedPayloadSeal.decide(null, despatch()) shouldBe SealDecision.AwaitPayload
    }

    @Test
    fun `INV-AC-13385 held bytes off their stated digest are refused when the despatch states it too`() {
        val wrong = flip(sha(xml))
        TransmittedPayloadSeal.decide(held(digest = wrong), despatch(digest = wrong)) shouldBe
            SealDecision.Refused(SealRefusal.HELD_BYTES_DIGEST_MISMATCH)
    }

    @Test
    fun `held bytes that do not match their stated digest are refused with a correct despatch digest`() {
        TransmittedPayloadSeal.decide(held(digest = flip(sha(xml))), despatch()) shouldBe
            SealDecision.Refused(SealRefusal.HELD_BYTES_DIGEST_MISMATCH)
    }

    @Test
    fun `bytesMatchDigest is exact string equality of the lower-case hex`() {
        held().bytesMatchDigest() shouldBe true
        held(digest = flip(sha(xml))).bytesMatchDigest() shouldBe false
        held(digest = sha(xml).uppercase()).bytesMatchDigest() shouldBe false
    }

    @Test
    fun `another version or another document is a held identity mismatch`() {
        TransmittedPayloadSeal.decide(held(), despatch(version = 2)) shouldBe
            SealDecision.Refused(SealRefusal.HELD_IDENTITY_MISMATCH)
        val other = UUID.fromString("99999999-2222-3333-4444-555555555555")
        TransmittedPayloadSeal.decide(held(), despatch(uuid = other)) shouldBe
            SealDecision.Refused(SealRefusal.HELD_IDENTITY_MISMATCH)
    }

    @Test
    fun `an upper-case despatch digest is refused`() {
        TransmittedPayloadSeal.decide(held(), despatch(digest = sha(xml).uppercase())) shouldBe
            SealDecision.Refused(SealRefusal.DESPATCHED_DIGEST_MISMATCH)
    }

    @Test
    fun `mutating the constructor array or the returned array does not change the sealed bytes`() {
        val src = xml.copyOf()
        val h = HeldPayload(doc, 1, src, sha(xml))
        src[0] = 0
        h.bytesMatchDigest() shouldBe true
        h.payloadBytes()[0] = 0
        val sealed = sealOf(TransmittedPayloadSeal.decide(h, despatch()))
        sealed.payloadBytes().toList() shouldBe xml.toList()
        sealed.payloadBytes()[0] = 0
        sealed.payloadBytes().toList() shouldBe xml.toList()
    }

    @Test
    fun `version 0 and attempt number 0 are rejected`() {
        shouldThrow<IllegalArgumentException> { HeldPayload(doc, 0, xml, sha(xml)) }
        shouldThrow<IllegalArgumentException> { Despatch(doc, 0, 1, sha(xml), at) }
        shouldThrow<IllegalArgumentException> { Despatch(doc, 1, 0, sha(xml), at) }
    }
}
