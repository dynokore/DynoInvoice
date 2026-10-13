package com.dynokore.platform.evidencestore

/**
 * The outcome of [EvidenceLedger.verifyChain] (FR-9.15 behaviour 4: "chain
 * verification is a routine job").
 */
sealed interface ChainVerificationResult {
    data class Valid(
        val recordCount: Int,
    ) : ChainVerificationResult

    /** The stored hash at [atSequence] does not match what the chain recomputes from its content and predecessor. */
    data class Broken(
        val atSequence: Long,
        val expected: ContentHash,
        val actual: ContentHash,
    ) : ChainVerificationResult
}
