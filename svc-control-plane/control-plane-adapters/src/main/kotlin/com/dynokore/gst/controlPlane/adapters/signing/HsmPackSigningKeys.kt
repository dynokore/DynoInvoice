package com.dynokore.gst.controlPlane.adapters.signing

import com.dynokore.gst.controlPlane.application.pack.PackSigningKeys
import com.dynokore.platform.crypto.JcaSigningKey
import com.dynokore.platform.crypto.SignatureAlgorithm
import com.dynokore.platform.crypto.SigningKey
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.util.Base64

/**
 * The application port [PackSigningKeys] over a [JcaSigningKey] on the JCA provider that holds the key (INV-Q-7459):
 * for the key store type `PKCS11` the private key stays in the HSM and signs there. The PIN and the certificate are
 * read through the SecretStore binding at each resolution (STK-29: rotation by file, no cache); the signing key itself
 * is read by reference only. No cloud-KMS adapter exists here.
 */
class HsmPackSigningKeys(
    private val settings: PackSigningSettings,
    private val secrets: SecretStore,
) : PackSigningKeys {
    override fun resolve(signingKeyRef: String): SigningKey? {
        if (signingKeyRef != settings.ref) return null
        val pin = secrets.read(SecretRef(settings.pinSecret)).reveal().toCharArray()
        val certificate =
            Base64.getMimeDecoder().decode(secrets.read(SecretRef(settings.certificateSecret)).reveal().trim())
        val store = keyStore(pin)
        val key = store.getKey(settings.alias, pin) as? PrivateKey
        checkNotNull(key) { "the key store holds no private key under the configured alias" }
        return JcaSigningKey(
            SignatureAlgorithm.ECDSA_P256_SHA256,
            key,
            certificate,
            store.provider.name.takeIf {
                settings.keyStoreType ==
                    PackSigningSettings.PKCS11
            },
        )
    }

    private fun keyStore(pin: CharArray): KeyStore {
        val store =
            settings.provider?.let { KeyStore.getInstance(settings.keyStoreType, it) }
                ?: KeyStore.getInstance(settings.keyStoreType)
        val path = settings.keyStorePath
        if (settings.keyStoreType == PackSigningSettings.PKCS11 || path == null) {
            store.load(null, pin)
        } else {
            File(path).inputStream().use { store.load(it, pin) }
        }
        return store
    }
}
