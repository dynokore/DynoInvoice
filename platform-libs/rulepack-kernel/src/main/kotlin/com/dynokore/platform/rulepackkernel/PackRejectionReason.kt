package com.dynokore.platform.rulepackkernel

/** Why [PackVerifier.verify] refused a [PackArtefact] (STK-13). */
enum class PackRejectionReason {
    /** The manifest or checksums member is not well-formed, or is missing a required field. */
    MANIFEST_MALFORMED,

    /** [PackArtefact.contents] does not hash to what [PackArtefact.checksums] declares. */
    CHECKSUM_MISMATCH,

    /** The CMS signature does not verify against the certificate it names. */
    SIGNATURE_INVALID,

    /** The signature verifies mathematically, but the signer is not one of the given [TrustAnchors]. */
    UNTRUSTED_SIGNER,
}
