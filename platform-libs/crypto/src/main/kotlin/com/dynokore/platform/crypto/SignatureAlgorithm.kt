package com.dynokore.platform.crypto

/**
 * The named signature algorithms of the platform (CS-53). A signer and a verifier name one; a
 * signature made with any other algorithm, digest or curve is refused.
 */
enum class SignatureAlgorithm(
    /** The signature algorithm OID in the CMS `SignerInfo` (RFC 5758 §3.2). */
    val signatureOid: String,
    /** The digest algorithm OID in the CMS `SignerInfo` (RFC 5754 §2.2). */
    val digestOid: String,
    /** The named-curve OID of the signer's public key (RFC 5480 §2.1.1.1). */
    val curveOid: String,
) {
    /** ECDSA over P-256 with SHA-256: the STK-13 format (packs, configuration bundles, certification reports). */
    ECDSA_P256_SHA256(
        signatureOid = "1.2.840.10045.4.3.2",
        digestOid = "2.16.840.1.101.3.4.2.1",
        curveOid = "1.2.840.10045.3.1.7",
    ),
}
