package com.dynokore.platform.rulepackkernel

/**
 * One trusted signing identity: a release train's own signing key, or a
 * pre-published successor key (STK-13: "trust anchors ship in each release
 * train, with at least one successor key pre-published for rotation").
 *
 * [certificateDer] is the raw DER-encoded X.509 certificate. Verification
 * pins the signer to an *exact* trust anchor — [certificateDer] must match a
 * signer's certificate byte for byte — rather than building an open PKIX
 * chain: `control-plane-svc`'s signing keys are not part of a public CA
 * hierarchy (STK-13), so pinning is the trust model, the same reasoning a
 * certificate-pinned client applies to a server certificate.
 *
 * [label] is a human-readable name (`"2026-train-primary"`,
 * `"2026-train-successor"`) for the decision trace and diagnostics — never
 * parsed or compared for trust, only [certificateDer] is.
 */
class TrustAnchor(
    val label: String,
    val certificateDer: ByteArray,
) {
    init {
        require(label.isNotBlank()) { "TrustAnchor label must not be blank" }
        require(certificateDer.isNotEmpty()) { "TrustAnchor certificateDer must not be empty" }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is TrustAnchor && label == other.label && certificateDer.contentEquals(other.certificateDer))

    override fun hashCode(): Int = 31 * label.hashCode() + certificateDer.contentHashCode()

    override fun toString(): String = "TrustAnchor(label='$label', certificateDer=${certificateDer.size}B)"
}
