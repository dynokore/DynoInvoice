package com.dynokore.gst.rules.adapters.packs

import com.dynokore.platform.crypto.HashAlgorithm
import com.dynokore.platform.crypto.Hashing
import com.dynokore.platform.crypto.JcaSigningKey
import com.dynokore.platform.crypto.SignatureAlgorithm
import com.dynokore.platform.crypto.SigningKey
import com.dynokore.platform.rulepackkernel.TrustAnchor
import com.dynokore.platform.rulepackkernel.TrustAnchors
import com.dynokore.platform.serviceruntime.binding.SecretNotFound
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** The signing identity of one holder copy write and the anchors a verifier of it trusts. */
class HolderCopyKeyMaterial(
    val signingKey: SigningKey,
    val anchors: TrustAnchors,
    val signingLabel: String,
)

/** A key secret that cannot be used; the text names the secret and never a key byte (STK-29, CS-24). */
class HolderCopyKeyUnavailable(
    val secret: String,
    reason: String,
    cause: Throwable? = null,
) : RuntimeException("holder copy signing is unavailable: secret '$secret' $reason", cause)

/**
 * The key sources of the holder copy signature (INV-Q-14650 A, INV-Q-16531 A; NIST SP 800-57 part 1: the successor
 * certificate is published before it signs): the SecretStore is read at every [load], so a replaced secret takes
 * effect on the next write without a restart (STK-29). `rules/active-packs-cursor-key` is never read here (key
 * separation). The anchors are the signing certificate first, then the optional `-next` and `-previous`
 * certificates; each anchor is labelled `rules-svc-holder-copy:` plus the first 16 hex characters of the SHA-256
 * of its DER, never its subject text.
 */
class HolderCopySigningKeys(
    private val secrets: SecretStore,
) {
    /** The material of one write, or a [HolderCopyKeyUnavailable] naming the secret that failed. */
    fun load(): HolderCopyKeyMaterial {
        val privateKey = privateKeyOf(KEY_SECRET, text(KEY_SECRET))
        val certificate = certificateOf(CERTIFICATE_SECRET, text(CERTIFICATE_SECRET))
        val extra =
            listOf(CERTIFICATE_NEXT_SECRET, CERTIFICATE_PREVIOUS_SECRET).mapNotNull { name ->
                optionalText(name)?.let { certificateOf(name, it) }
            }
        val anchors = (listOf(certificate) + extra).map { TrustAnchor(labelOf(it), it) }
        return HolderCopyKeyMaterial(
            JcaSigningKey(SignatureAlgorithm.ECDSA_P256_SHA256, privateKey, certificate),
            TrustAnchors(anchors),
            labelOf(certificate),
        )
    }

    private fun text(name: String): String = optionalText(name) ?: throw HolderCopyKeyUnavailable(name, "is not bound")

    /** The secret's text, or null when none is bound; an unreadable mount is a failure that names the secret. */
    private fun optionalText(name: String): String? =
        try {
            secrets.read(SecretRef(name)).reveal()
        } catch (_: SecretNotFound) {
            null
        } catch (e: IOException) {
            throw HolderCopyKeyUnavailable(name, "cannot be read", e)
        }

    private fun privateKeyOf(
        name: String,
        pem: String,
    ): ECPrivateKey = parseP256(pem) ?: throw HolderCopyKeyUnavailable(name, "is not a PKCS#8 PEM P-256 key")

    private fun parseP256(pem: String): ECPrivateKey? {
        val body = pemBody(pem, PKCS8_LABEL)
        val key =
            try {
                body?.let { KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(it)) }
            } catch (_: GeneralSecurityException) {
                null
            }
        return (key as? ECPrivateKey)?.takeIf { isP256(it.params) }
    }

    private fun certificateOf(
        name: String,
        pem: String,
    ): ByteArray {
        val body =
            pemBody(pem, CERTIFICATE_LABEL) ?: throw HolderCopyKeyUnavailable(name, "is not an X.509 PEM certificate")
        try {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(body))
        } catch (_: GeneralSecurityException) {
            throw HolderCopyKeyUnavailable(name, "is not an X.509 PEM certificate")
        }
        return body
    }

    /** The DER between exactly one `BEGIN`/`END` pair of [label]; anything else (another label, extra text) is null. */
    private fun pemBody(
        pem: String,
        label: String,
    ): ByteArray? {
        val match = Regex("""\A\s*-----BEGIN $label-----([A-Za-z0-9+/=\s]+)-----END $label-----\s*\z""").find(pem)
        val base64 = match?.groupValues?.get(1)?.filterNot { it.isWhitespace() } ?: return null
        return try {
            Base64.getDecoder().decode(base64)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun isP256(params: ECParameterSpec): Boolean {
        val named = AlgorithmParameters.getInstance("EC")
        named.init(ECGenParameterSpec("secp256r1"))
        val p256 = named.getParameterSpec(ECParameterSpec::class.java)
        return params.curve == p256.curve && params.generator == p256.generator && params.order == p256.order
    }

    companion object {
        const val KEY_SECRET = "rules/holder-copy-signing-key"
        const val CERTIFICATE_SECRET = "rules/holder-copy-signing-certificate"
        const val CERTIFICATE_NEXT_SECRET = "rules/holder-copy-signing-certificate-next"
        const val CERTIFICATE_PREVIOUS_SECRET = "rules/holder-copy-signing-certificate-previous"
        const val LABEL_PREFIX = "rules-svc-holder-copy:"
        private const val LABEL_HEX_CHARS = 16
        private const val PKCS8_LABEL = "PRIVATE KEY"
        private const val CERTIFICATE_LABEL = "CERTIFICATE"

        /** `rules-svc-holder-copy:` and the first 16 lower-case hex characters of the SHA-256 of the DER. */
        fun labelOf(certificateDer: ByteArray): String =
            LABEL_PREFIX + Hashing.hex(HashAlgorithm.SHA_256, certificateDer).take(LABEL_HEX_CHARS)
    }
}
