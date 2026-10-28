package com.dynokore.gst.evidence.domain.digest

import java.security.MessageDigest

/** The three document-bound chains a period digest anchors, in the fixed order of INV-Q-7251 A (2). */
enum class DigestChainType {
    PAYLOAD,
    ACKNOWLEDGEMENT,
    DECISION_TRACE,
}

/**
 * The state of one chain at the filing: its [length] and the `contentHash` of its last record ([tailHash], null for an
 * empty chain, which the digest text writes as `-`).
 */
data class ChainTail(
    val type: DigestChainType,
    val length: Long,
    val tailHash: String?,
) {
    init {
        require(length >= 0) { "a chain length is not negative" }
        require((length == 0L) == (tailHash == null)) { "a chain has a tail hash exactly when it is not empty" }
        require(tailHash == null || TAIL_HASH.matches(tailHash)) { "a tail hash is 64 lower-case hex characters" }
    }

    /** The tail as the digest text and the store write it: the hash, or `-` for an empty chain. */
    val tailText: String get() = tailHash ?: EMPTY_TAIL

    companion object {
        const val EMPTY_TAIL = "-"
        private val TAIL_HASH = Regex("^[0-9a-f]{64}$")
    }
}

const val PERIOD_DIGEST_PREFIX = "sha256:"

/**
 * The period digest of INV-Q-7251 A (2): `sha256:` and the 64 lower-case hex characters of the SHA-256 of the UTF-8
 * text of the lines `entityRef|period|returnVersion` and, per chain in the order of [DigestChainType],
 * `TYPE|length|tailHash`, joined by a line feed with no trailing one. [chains] holds each type exactly once, in any
 * order.
 */
fun periodDigest(
    entityRef: String,
    period: String,
    returnVersion: Int,
    chains: List<ChainTail>,
): String {
    require(chains.map { it.type }.sorted() == DigestChainType.entries) { "each chain type is given exactly once" }
    val lines =
        listOf("$entityRef|$period|$returnVersion") +
            chains.sortedBy { it.type }.map { "${it.type.name}|${it.length}|${it.tailText}" }
    val hash = MessageDigest.getInstance("SHA-256").digest(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
    return PERIOD_DIGEST_PREFIX + hash.joinToString("") { "%02x".format(it) }
}
