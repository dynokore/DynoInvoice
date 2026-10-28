package com.dynokore.gst.evidence.domain.digest

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class PeriodDigestTest {
    private val h1 = "a".repeat(64)
    private val h2 = "b".repeat(64)

    private fun chains() =
        listOf(
            ChainTail(DigestChainType.PAYLOAD, 3, h1),
            ChainTail(DigestChainType.ACKNOWLEDGEMENT, 2, h2),
            ChainTail(DigestChainType.DECISION_TRACE, 0, null),
        )

    private fun sha(text: String) =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private val specified = "E-001|2026-Q1|1\nPAYLOAD|3|$h1\nACKNOWLEDGEMENT|2|$h2\nDECISION_TRACE|0|-"

    @Test
    fun `the digest is sha256 and the hex of the specified text, 71 characters`() {
        val digest = periodDigest("E-001", "2026-Q1", 1, chains())
        digest shouldBe "sha256:" + sha(specified)
        digest.length shouldBe 71
    }

    @Test
    fun `the swapped chain order gives another digest than the specified one`() {
        val swapped = "E-001|2026-Q1|1\nACKNOWLEDGEMENT|2|$h2\nPAYLOAD|3|$h1\nDECISION_TRACE|0|-"
        periodDigest("E-001", "2026-Q1", 1, chains()) shouldNotBe "sha256:" + sha(swapped)
    }

    @Test
    fun `chains given in another order give the same digest`() {
        periodDigest("E-001", "2026-Q1", 1, chains().reversed()) shouldBe periodDigest("E-001", "2026-Q1", 1, chains())
    }

    @Test
    fun `a trailing line feed is not part of the text`() {
        periodDigest("E-001", "2026-Q1", 1, chains()) shouldNotBe "sha256:" + sha("$specified\n")
    }

    @Test
    fun `an empty chain is written as a dash, not as an empty text`() {
        periodDigest("E-001", "2026-Q1", 1, chains()) shouldNotBe "sha256:" + sha(specified.removeSuffix("-"))
    }

    @Test
    fun `the hex is lower case`() {
        val hex = periodDigest("E-001", "2026-Q1", 1, chains()).removePrefix("sha256:")
        hex shouldBe hex.lowercase()
        Regex("^[0-9a-f]{64}$").matches(hex) shouldBe true
    }

    @Test
    fun `a missing, repeated or inconsistent chain is refused`() {
        shouldThrow<IllegalArgumentException> { periodDigest("E", "P", 1, chains().drop(1)) }
        shouldThrow<IllegalArgumentException> { periodDigest("E", "P", 1, chains() + chains().first()) }
        shouldThrow<IllegalArgumentException> { ChainTail(DigestChainType.PAYLOAD, 0, h1) }
        shouldThrow<IllegalArgumentException> { ChainTail(DigestChainType.PAYLOAD, 1, null) }
        shouldThrow<IllegalArgumentException> { ChainTail(DigestChainType.PAYLOAD, 1, h1.uppercase()) }
    }
}
