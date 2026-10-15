package com.dynokore.platform.crypto

import java.time.Instant

/**
 * Verifies a detached CMS signature (STK-13) like [DetachedSignatureVerifier], but trusts the signer
 * through a certificate path instead of an exact pin: a path must lead from the signer certificate
 * to a certificate in the trust anchor set (INV-Q-3371 A, INV-Q-5093 A). The `crypto` (impl) half
 * provides it.
 *
 * The signature checks come first and fail as [DetachedVerification.SignatureInvalid]: the message
 * parses as CMS `SignedData`, the signer is the certificate its own `SignerInfo` names, it uses the
 * named [SignatureAlgorithm], and the signature verifies over the content. Then the path rules, the
 * first failure ending the check with [DetachedVerification.UntrustedSigner]:
 * - the certificates the message carries are candidate intermediates only and are never trusted;
 * - a path leads from the signer through the candidates to a certificate equal, byte for byte, to
 *   one in the anchor list (a root, a CA, or the signing certificate itself), at most 8 certificates;
 * - each link's issuer name equals the next subject name and its signature verifies under the next
 *   certificate's key;
 * - every certificate on the path, the anchor included, is valid at the instant the caller passes
 *   (the module never reads the clock);
 * - every issuer has basicConstraints CA=true, keyCertSign when keyUsage is present, and its
 *   pathLenConstraint is not exceeded;
 * - the signer has digitalSignature when keyUsage is present and is not a CA certificate;
 * - a certificate with an unknown critical extension is refused;
 * - an empty anchor list yields [DetachedVerification.UntrustedSigner].
 *
 * No revocation check (CRL, OCSP) is made: removing an anchor from the installation's set is the
 * revocation path (INV-Q-5093).
 */
fun interface ChainedSignatureVerifier {
    /**
     * [DetachedVerification.Verified.trustedIndex] is the index in [trustAnchorsDer] of the anchor the path ends at.
     */
    fun verify(
        content: ByteArray,
        signature: ByteArray,
        algorithm: SignatureAlgorithm,
        trustAnchorsDer: List<ByteArray>,
        at: Instant,
    ): DetachedVerification
}
