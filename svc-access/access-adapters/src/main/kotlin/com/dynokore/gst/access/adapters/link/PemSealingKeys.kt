package com.dynokore.gst.access.adapters.link

import com.dynokore.gst.access.application.link.SealingKey
import com.dynokore.gst.access.application.link.SealingKeys
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** The start-up key cannot be used: absent, unreadable, not RSA or shorter than 3072 bits (fail closed). */
class SealingKeyUnusable(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * notification-svc's delivery public key, read once at start-up from the SecretStore binding (ref
 * [SECRET_REF], PEM SubjectPublicKeyInfo). Any failure to read it stops the start-up: a passcode is never sent
 * unsealed and never sealed to a weak key (INV-Q-17195 A).
 */
class PemSealingKeys private constructor(
    private val key: SealingKey,
) : SealingKeys {
    override fun current(): SealingKey = key

    companion object {
        const val SECRET_REF = "message-delivery-sealing-public-key"
        private const val BEGIN = "-----BEGIN PUBLIC KEY-----"
        private const val END = "-----END PUBLIC KEY-----"

        /** Reads the key with [read] (the SecretStore), once. */
        fun load(read: () -> String): PemSealingKeys {
            val pem =
                try {
                    read()
                } catch (e: Exception) {
                    throw SealingKeyUnusable("the secret $SECRET_REF cannot be read", e)
                }
            return PemSealingKeys(parse(pem))
        }

        fun parse(pem: String): SealingKey {
            val text = pem.trim()
            if (!text.startsWith(BEGIN) || !text.endsWith(END)) {
                throw SealingKeyUnusable("$SECRET_REF is not a PEM SubjectPublicKeyInfo")
            }
            val body = text.removePrefix(BEGIN).removeSuffix(END).filterNot { it.isWhitespace() }
            val der =
                try {
                    Base64.getDecoder().decode(body)
                } catch (e: IllegalArgumentException) {
                    throw SealingKeyUnusable("$SECRET_REF is not base64", e)
                }
            val key =
                try {
                    // the RSA factory accepts RSA keys only: an EC or other key is refused here
                    KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
                } catch (e: java.security.GeneralSecurityException) {
                    throw SealingKeyUnusable("$SECRET_REF is not an RSA public key", e)
                }
            if (key !is RSAPublicKey) throw SealingKeyUnusable("$SECRET_REF is not an RSA public key")
            if (key.modulus.bitLength() < SealingKey.MIN_BITS) {
                throw SealingKeyUnusable("$SECRET_REF is shorter than ${SealingKey.MIN_BITS} bits")
            }
            return SealingKey(SealingKey.keyIdOf(key), key)
        }
    }
}
