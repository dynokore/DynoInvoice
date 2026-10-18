package com.dynokore.platform.serviceruntime.binding

import com.dynokore.platform.tenancykernel.BindingKeySource
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * tenancy-kernel's [BindingKeySource] over the [SecretStore] (INV-T-5360, INV-REQ-854 b7; coordinator rulings
 * INV-Q-5352, INV-Q-5354). Like [com.dynokore.platform.serviceruntime.datasource.SecretStoreCredentialsProvider]
 * (INV-T-3913) it reads on every call and caches nothing, so each transaction start signs with the key mounted at that
 * moment: a rotated secret needs no restart. The key is the UTF-8 bytes of the secret value, with no decoding (INV-Q-6576):
 * this class is the one converter. Each call hands out a new array. A missing, empty or white-space-only secret
 * answers null.
 *
 * CS-24: no key and no secret value is logged or put in a message; [toString] names the two references only.
 */
class SecretStoreBindingKeySource(
    private val secrets: SecretStore,
    private val currentRef: SecretRef = CURRENT,
    private val previousRef: SecretRef = PREVIOUS,
) : BindingKeySource {
    override fun currentKey(): ByteArray? = read(currentRef)

    override fun previousKey(): ByteArray? = read(previousRef)

    private fun read(ref: SecretRef): ByteArray? =
        try {
            secrets
                .read(ref)
                .reveal()
                .takeIf { it.isNotBlank() }
                ?.toByteArray(Charsets.UTF_8)
        } catch (_: SecretNotFound) {
            null
        }

    override fun toString(): String = "SecretStoreBindingKeySource(current=$currentRef, previous=$previousRef)"

    companion object {
        /** The current installation binding key. */
        val CURRENT = SecretRef("tenancy/binding-key-current")

        /** The previous key, present only while a rotation is in flight. */
        val PREVIOUS = SecretRef("tenancy/binding-key-previous")
    }
}

/**
 * The runner's key source, or none: [source] is null when no [SecretStore] is bound or the current secret is missing.
 * Then the transaction runners are built unsigned (the key parameter is optional until INV-T-4156, which turns this
 * into a start-up failure) and [of] logs one warning that names the secret, never a value.
 */
class InstallationBindingKey(
    val source: BindingKeySource?,
) {
    companion object {
        private val log: Logger = LoggerFactory.getLogger(InstallationBindingKey::class.java)

        fun of(secrets: SecretStore?): InstallationBindingKey {
            val source = secrets?.let { SecretStoreBindingKeySource(it) }
            if (source == null || source.currentKey() == null) {
                log.warn(
                    "the current binding key secret '{}' is not available: the transaction runner signs no tenant binding",
                    SecretStoreBindingKeySource.CURRENT,
                )
                return InstallationBindingKey(null)
            }
            return InstallationBindingKey(source)
        }
    }
}
