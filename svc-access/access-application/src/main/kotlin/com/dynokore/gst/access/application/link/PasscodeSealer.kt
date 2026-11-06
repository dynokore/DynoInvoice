package com.dynokore.gst.access.application.link

import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/** notification-svc's RSA public delivery key with its id (first 8 bytes of SHA-256 of the DER SPKI, 16 hex digits). */
class SealingKey(
    val keyId: String,
    val key: RSAPublicKey,
) {
    init {
        require(key.modulus.bitLength() >= MIN_BITS) { "the delivery key is at least $MIN_BITS bits" }
        require(KEY_ID.matches(keyId)) { "the key id is 16 lower-case hex digits" }
    }

    companion object {
        const val MIN_BITS = 3072
        private val KEY_ID = Regex("^[0-9a-f]{16}$")

        /** The key id the form defines for [key]. */
        fun keyIdOf(key: PublicKey): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.encoded), 0, KEY_ID_BYTES)

        private const val KEY_ID_BYTES = 8
    }
}

/** Where the use case gets the public delivery key; reading it at start-up is the adapter's. */
fun interface SealingKeys {
    fun current(): SealingKey
}

/** Seals a passcode for the one recipient address of its message. */
fun interface PasscodeSealer {
    fun seal(
        passcode: String,
        recipientAddress: String,
    ): String
}

/**
 * `v1.<keyId>.<ciphertext>` (ruling INV-Q-17195 A, INV-T-17210): RSAES-OAEP, SHA-256, MGF1-SHA-256, the label the UTF-8
 * bytes of the recipient address, the ciphertext base64url without padding. JDK standard provider only.
 */
class OaepPasscodeSealer(
    private val keys: SealingKeys,
) : PasscodeSealer {
    override fun seal(
        passcode: String,
        recipientAddress: String,
    ): String {
        val sealing = keys.current()
        val spec =
            OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified(recipientAddress.toByteArray(Charsets.UTF_8)),
            )
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sealing.key, spec)
        val sealed = cipher.doFinal(passcode.toByteArray(Charsets.UTF_8))
        return "v1.${sealing.keyId}.${Base64.getUrlEncoder().withoutPadding().encodeToString(sealed)}"
    }
}
