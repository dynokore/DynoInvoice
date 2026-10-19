package com.dynokore.platform.evidencestore

import java.nio.charset.StandardCharsets

/**
 * FR-9.15 behaviour 2's chaining formula: each record's hash covers its own
 * content plus the previous record's hash, so altering or removing any one
 * record changes every hash after it ([ChainVerificationResult] is the check
 * for that). The first record of a chain has no previous hash — its link
 * input is the empty string, not a null special-cased at every call site.
 */
object HashChain {
    fun link(
        previous: ContentHash?,
        content: ByteArray,
    ): ContentHash {
        val prefix = previous?.hex.orEmpty().toByteArray(StandardCharsets.US_ASCII)
        return ContentHash.of(prefix + content)
    }

    /**
     * Recomputes the chain from [contents] in order and compares it against
     * the [storedHashes] recorded alongside them (same size, same order —
     * [JdbcEvidenceLedger.verifyChain] is this applied to one (tenant, entity,
     * period) chain's stored rows; a pure function so the property test for
     * chain integrity (STK-38, done-when) runs at in-memory speed, with no
     * database).
     */
    fun verify(
        contents: List<ByteArray>,
        storedHashes: List<ContentHash>,
    ): ChainVerificationResult {
        require(contents.size == storedHashes.size) { "contents and storedHashes must be the same size" }
        var previous: ContentHash? = null
        contents.forEachIndexed { index, content ->
            val expected = link(previous, content)
            val actual = storedHashes[index]
            if (expected != actual) return ChainVerificationResult.Broken(index.toLong(), expected, actual)
            previous = actual
        }
        return ChainVerificationResult.Valid(contents.size)
    }
}
