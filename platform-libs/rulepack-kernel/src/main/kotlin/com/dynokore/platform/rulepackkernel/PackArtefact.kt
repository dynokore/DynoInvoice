package com.dynokore.platform.rulepackkernel

/**
 * The signed pack artefact exactly as distributed (STK-13; `RulePackStaged`'s
 * payload, packs.yaml: "the signed artefact — manifest, checksums, signature
 * and contents"): four named byte members, never parsed or trusted until
 * [PackVerifier.verify] says so.
 *
 * - [manifest] — the pack's declared identity: [PackId], [PackType],
 *   [PackVersionLabel], effective dates, minimum engine version, and the
 *   content checksums it asserts (JSON, UTF-8).
 * - [checksums] — the SHA-256 digest(s) an independent verifier recomputes
 *   [contents] against, so a manifest cannot simply assert a checksum for
 *   content it does not match (JSON, UTF-8; `{"contents": "<hex>"}`).
 * - [signature] — a detached CMS (`SignedData`) signature (STK-13: ECDSA P-256
 *   with SHA-256) over [manifest] and [checksums] concatenated, in that order.
 * - [contents] — the declarative pack data itself (JSON, UTF-8), the thing
 *   [PackContentValidator] checks for executable content (INV-AC-2394,
 *   INV-REQ-2393).
 *
 * Not a `data class`: `ByteArray`'s structural inequality under the default
 * `equals`/`hashCode` is a well-known trap, so both are written out by hand
 * against [ByteArray.contentEquals] instead of silently doing the wrong thing.
 */
class PackArtefact(
    val manifest: ByteArray,
    val checksums: ByteArray,
    val signature: ByteArray,
    val contents: ByteArray,
) {
    override fun equals(other: Any?): Boolean = this === other ||
        (
            other is PackArtefact &&
                manifest.contentEquals(other.manifest) &&
                checksums.contentEquals(other.checksums) &&
                signature.contentEquals(other.signature) &&
                contents.contentEquals(other.contents)
            )

    override fun hashCode(): Int = arrayOf(
        manifest.contentHashCode(),
        checksums.contentHashCode(),
        signature.contentHashCode(),
        contents.contentHashCode(),
    ).contentHashCode()

    override fun toString(): String = "PackArtefact(manifest=${manifest.size}B, checksums=${checksums.size}B, " +
        "signature=${signature.size}B, contents=${contents.size}B)" // never the bytes themselves (CS-34)
}
