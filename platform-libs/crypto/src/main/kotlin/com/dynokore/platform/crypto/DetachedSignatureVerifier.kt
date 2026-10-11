package com.dynokore.platform.crypto

/**
 * Verifies a detached CMS signature (STK-13) over content that travels separately, under a named
 * [SignatureAlgorithm], with the signer pinned to an exact trusted certificate: the signer's
 * certificate must equal one of the trusted certificates byte for byte, never a certificate the
 * message merely carries. The `crypto` (impl) half provides it.
 *
 * Steps, in order, the first failure ending the check: the signature parses as CMS `SignedData`
 * with a `SignerInfo` and a matching certificate ([DetachedVerification.SignatureInvalid]
 * otherwise); the signer uses [SignatureAlgorithm] (same); the signature verifies over the
 * content against that certificate (same); that certificate is one of the trusted ones
 * ([DetachedVerification.UntrustedSigner] otherwise).
 */
fun interface DetachedSignatureVerifier {
    fun verify(
        content: ByteArray,
        signature: ByteArray,
        algorithm: SignatureAlgorithm,
        trustedCertificatesDer: List<ByteArray>,
    ): DetachedVerification
}

/** The outcome of [DetachedSignatureVerifier.verify]. */
sealed interface DetachedVerification {
    /** Verified; [trustedIndex] is the index of the first trusted certificate equal to the signer's. */
    data class Verified(
        val trustedIndex: Int,
    ) : DetachedVerification

    /** Not a valid signature over the content under the named algorithm; [detail] says why. */
    data class SignatureInvalid(
        val detail: String,
    ) : DetachedVerification

    /** A valid signature, but by a certificate that is none of the trusted ones. */
    data object UntrustedSigner : DetachedVerification
}
