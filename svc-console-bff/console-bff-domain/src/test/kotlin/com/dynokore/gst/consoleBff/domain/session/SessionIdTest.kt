package com.dynokore.gst.consoleBff.domain.session

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class SessionIdTest {
    private val ok = "A".repeat(43)

    private fun isMalformed(v: String) = SessionId.parse(v) == SessionIdParse.Malformed

    @Test
    fun `INV-AC-14455 only 43 base64url characters are accepted`() {
        isMalformed(ok) shouldBe false
        isMalformed("a-_Z09".padEnd(43, 'x')) shouldBe false
        for (bad in listOf(
            "",
            "A".repeat(42),
            "A".repeat(44),
            "A".repeat(42) + "=",
            "A".repeat(42) + " ",
            "A".repeat(42) + "é",
            "A".repeat(21) + "," + "A".repeat(21),
            ok + ",$ok",
            "A".repeat(42) + "\n",
            "A".repeat(42) + "+",
            "A".repeat(42) + "/",
        )) {
            isMalformed(bad) shouldBe true
        }
    }

    @Test
    fun `INV-AC-14455 hash is the lower-case sha256 hex of the utf8 bytes`() {
        val id = (SessionId.parse(ok) as SessionIdParse.Parsed).id
        val expected =
            MessageDigest
                .getInstance("SHA-256")
                .digest(ok.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
        SessionIdHash.of(id) shouldBe expected
        SessionIdHash.of(id).length shouldBe 64
    }

    @Test
    fun `INV-AC-14455 toString never contains the value`() {
        val v = "Qx9".repeat(14) + "Z"
        val parsed = SessionId.parse(v)
        parsed.toString().contains(v) shouldBe false
        (parsed as SessionIdParse.Parsed).id.toString().contains(v) shouldBe false
    }
}
