package com.dynokore.platform.crypto

import java.io.ByteArrayInputStream
import java.security.GeneralSecurityException
import java.security.Provider
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date

/**
 * The path rules of [ChainedSignatureVerifier], over JCA certificates and on the given [provider].
 * Every rule fails closed: a certificate or a link that breaks one is never used in a path.
 */
internal class CertificatePathValidator(
    private val provider: Provider,
    private val anchorsDer: List<ByteArray>,
    private val at: Instant,
    private val signatureOid: String,
) {
    private val anchors: List<X509Certificate> = anchorsDer.mapNotNull { parse(it) }

    /** The index in the anchor list of the anchor a valid path from [signer] ends at, or `null`. */
    fun trustedIndex(
        signer: X509Certificate,
        carried: List<X509Certificate>,
    ): Int? {
        if (anchorsDer.isEmpty() || !signerAcceptable(signer)) {
            return null
        }
        return extend(listOf(signer), carried + anchors, mutableSetOf())
    }

    private fun extend(
        path: List<X509Certificate>,
        pool: List<X509Certificate>,
        deadEnds: MutableSet<Pair<Int, Int>>,
    ): Int? {
        val current = path.last()
        val anchorIndex = anchorsDer.indexOfFirst { it.contentEquals(current.encoded) }
        if (anchorIndex >= 0) {
            return anchorIndex
        }
        if (path.size >= MAX_PATH_LENGTH) {
            return null
        }
        for ((poolIndex, issuer) in pool.withIndex()) {
            val state = poolIndex to path.size
            val onPath = path.any { it.encoded.contentEquals(issuer.encoded) }
            if (!onPath && state !in deadEnds && issuerAcceptable(current, issuer, path.size)) {
                extend(path + issuer, pool, deadEnds)?.let { return it }
                // a certificate at a position that led nowhere is never searched again: the search is
                // polynomial in the pool size and the path length, whatever the message carries
                deadEnds += state
            }
        }
        return null
    }

    private fun signerAcceptable(signer: X509Certificate): Boolean =
        certificateAcceptable(signer) &&
            signer.basicConstraints < 0 &&
            (signer.keyUsage?.get(KU_DIGITAL_SIGNATURE) ?: true)

    /** [issuer] would be the certificate at [position] in the path, directly above [subject]. */
    private fun issuerAcceptable(
        subject: X509Certificate,
        issuer: X509Certificate,
        position: Int,
    ): Boolean {
        val pathLen = issuer.basicConstraints // -1: not a CA; Int.MAX_VALUE: a CA without a pathLenConstraint
        val isCa = pathLen >= 0
        val withinPathLen = !isCa || position - 1 <= pathLen
        return certificateAcceptable(issuer) &&
            subject.issuerX500Principal == issuer.subjectX500Principal &&
            subject.sigAlgOID == signatureOid &&
            signedBy(subject, issuer) &&
            isCa &&
            (issuer.keyUsage?.get(KU_KEY_CERT_SIGN) ?: true) &&
            withinPathLen
    }

    private fun signedBy(
        subject: X509Certificate,
        issuer: X509Certificate,
    ): Boolean =
        try {
            subject.verify(issuer.publicKey, provider)
            true
        } catch (_: GeneralSecurityException) {
            false
        }

    private fun certificateAcceptable(certificate: X509Certificate): Boolean {
        val valid =
            try {
                certificate.checkValidity(Date.from(at))
                true
            } catch (_: GeneralSecurityException) {
                false
            }
        return valid && (certificate.criticalExtensionOIDs.orEmpty() - KNOWN_CRITICAL).isEmpty()
    }

    private fun parse(der: ByteArray): X509Certificate? = parseCertificate(provider, der)

    companion object {
        /** The most certificates a message may carry besides the signer; more is refused before any path search. */
        const val MAX_CARRIED = 16

        /** Path length in certificates, signer and anchor included. */
        const val MAX_PATH_LENGTH = 8

        private const val KU_DIGITAL_SIGNATURE = 0
        private const val KU_KEY_CERT_SIGN = 5

        /** Critical extensions this validator understands: basicConstraints, keyUsage, subjectAltName. */
        private val KNOWN_CRITICAL = setOf("2.5.29.19", "2.5.29.15", "2.5.29.17")

        fun parseCertificate(
            provider: Provider,
            der: ByteArray,
        ): X509Certificate? =
            try {
                CertificateFactory
                    .getInstance("X.509", provider)
                    .generateCertificate(ByteArrayInputStream(der)) as? X509Certificate
            } catch (_: GeneralSecurityException) {
                null
            }
    }
}
