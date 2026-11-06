package com.dynokore.gst.transport.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SenderVerifierTest {
    private val now = Instant.parse("2026-11-01T10:00:00Z")
    private val verifier = SenderVerifier(Clock.fixed(now, ZoneOffset.UTC))
    private val secret = "primary-secret".toByteArray()
    private val body = """{"apNotificationId":"APN-1","kind":"ACKNOWLEDGEMENT"}""".toByteArray()

    private fun mac(
        key: ByteArray,
        data: ByteArray,
    ): String =
        Mac
            .getInstance("HmacSHA256")
            .apply { init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal(data)
            .let {
                java.util.HexFormat
                    .of()
                    .formatHex(it)
            }

    private fun signed(
        ts: String,
        b: ByteArray = body,
        key: ByteArray = secret,
    ) = "sha256=" + mac(key, ts.toByteArray() + '.'.code.toByte() + b)

    private fun ageSeconds(s: Long) = (now.epochSecond - s).toString()

    private fun check(
        ts: String?,
        sig: String?,
        b: ByteArray = body,
        key: ByteArray = secret,
    ) = verifier.verify(b, ts, sig, key)

    private fun accepted(ts: String) {
        check(ts, signed(ts)) shouldBe SenderVerdict.Accepted
    }

    private fun refused(
        v: SenderVerdict,
        reason: SenderRefusal,
    ) {
        v shouldBe SenderVerdict.Refused(reason)
    }

    @Test
    fun `299 seconds old is accepted`() = accepted(ageSeconds(299))

    @Test
    fun `exactly 300 seconds old is accepted`() = accepted(ageSeconds(300))

    @Test
    fun `300 seconds in the future is accepted`() = accepted(ageSeconds(-300))

    @Test
    fun `301 seconds old is refused`() {
        val ts = ageSeconds(301)
        refused(check(ts, signed(ts)), SenderRefusal.TIMESTAMP_STALE)
    }

    @Test
    fun `301 seconds in the future is refused`() {
        val ts = ageSeconds(-301)
        refused(check(ts, signed(ts)), SenderRefusal.TIMESTAMP_STALE)
    }

    @Test
    fun `no timestamp is refused`() = refused(check(null, signed(ageSeconds(0))), SenderRefusal.NO_TIMESTAMP)

    private fun notInteger(ts: String?) {
        val shown = ts ?: "null"
        check(ts, signed(shown)) shouldBe SenderVerdict.Refused(SenderRefusal.TIMESTAMP_NOT_INTEGER)
    }

    @Test
    fun `a non-numeric timestamp is refused`() = notInteger("yesterday")

    @Test
    fun `an empty timestamp is refused as non-numeric`() = notInteger("")

    @Test
    fun `a leading plus is refused`() = notInteger("+" + ageSeconds(0))

    @Test
    fun `a leading minus is refused`() = notInteger("-" + ageSeconds(0))

    @Test
    fun `a leading space is refused`() = notInteger(" " + ageSeconds(0))

    @Test
    fun `a trailing space is refused`() = notInteger(ageSeconds(0) + " ")

    @Test
    fun `a decimal point is refused`() = notInteger(ageSeconds(0) + ".0")

    @Test
    fun `Arabic-Indic digits of the right value are refused`() =
        notInteger(ageSeconds(0).map { '\u0660' + (it - '0') }.joinToString(""))

    @Test
    fun `19 digits are refused as non-numeric`() = notInteger("0" + ageSeconds(0).padStart(18, '0'))

    @Test
    fun `18 digits that are 301 seconds off are refused as stale, not as non-numeric`() {
        val ts = ageSeconds(301).padStart(18, '0')
        ts.length shouldBe 18
        refused(check(ts, signed(ts)), SenderRefusal.TIMESTAMP_STALE)
    }

    @Test
    fun `18 digits that are 300 seconds off are accepted and signed as received`() =
        accepted(ageSeconds(300).padStart(18, '0'))

    @Test
    fun `a signature over the body alone is refused`() {
        val ts = ageSeconds(0)
        refused(check(ts, "sha256=" + mac(secret, body)), SenderRefusal.SIGNATURE_MISMATCH)
    }

    @Test
    fun `upper-case hex is refused`() {
        val ts = ageSeconds(0)
        val sig = signed(ts)
        refused(check(ts, "sha256=" + sig.removePrefix("sha256=").uppercase()), SenderRefusal.SIGNATURE_MISMATCH)
    }

    @Test
    fun `the prefix sha1 is refused`() {
        val ts = ageSeconds(0)
        refused(check(ts, signed(ts).replace("sha256=", "sha1=")), SenderRefusal.SIGNATURE_MISMATCH)
    }

    @Test
    fun `no prefix and no signature are refused`() {
        val ts = ageSeconds(0)
        refused(check(ts, signed(ts).removePrefix("sha256=")), SenderRefusal.SIGNATURE_MISMATCH)
        refused(check(ts, null), SenderRefusal.NO_SIGNATURE)
    }

    @Test
    fun `another adapter's secret is refused`() {
        val ts = ageSeconds(0)
        refused(check(ts, signed(ts, key = "other-secret".toByteArray())), SenderRefusal.SIGNATURE_MISMATCH)
    }

    @Test
    fun `one changed body character is refused`() {
        val ts = ageSeconds(0)
        val changed = body.copyOf().also { it[10] = 'X'.code.toByte() }
        refused(check(ts, signed(ts), b = changed), SenderRefusal.SIGNATURE_MISMATCH)
    }

    @Test
    fun `an empty secret is refused even with a signature made with it`() {
        val ts = ageSeconds(0)
        refused(check(ts, signed(ts, key = ByteArray(1)), key = ByteArray(0)), SenderRefusal.NO_SECRET)
    }

    @Test
    fun `a signature with trailing or leading characters is refused`() {
        val ts = ageSeconds(0)
        refused(check(ts, signed(ts) + "0"), SenderRefusal.SIGNATURE_MISMATCH)
        refused(check(ts, " " + signed(ts)), SenderRefusal.SIGNATURE_MISMATCH)
    }
}
