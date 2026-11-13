package com.dynokore.gst.transport.adapters.vault

import com.dynokore.gst.transport.application.CredentialRef
import com.dynokore.gst.transport.application.CredentialVault
import com.dynokore.gst.transport.application.Secret
import com.dynokore.platform.serviceruntime.binding.SecretNotFound
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore

/**
 * The [CredentialVault] over the platform `SecretStore` (STK-29: mounted files populated from the vault; no direct
 * vault SDK). Every call reads by reference at call time and keeps nothing: [holds] reads and drops the value and
 * answers a boolean, [read] hands the value to the caller in a redacting [Secret]. No value is stored, logged or put
 * in a message.
 * The credential reference is the `SecretRef` of the secret. A reference that is not a valid `SecretRef` (blank or
 * traversing) is held by nothing.
 */
class SecretStoreCredentialVault(
    private val secrets: SecretStore,
) : CredentialVault {
    override fun holds(ref: CredentialRef): Boolean = lookup(ref) != null

    override fun read(ref: CredentialRef): Secret? = lookup(ref)?.let { Secret(it.reveal().toCharArray()) }

    private fun lookup(ref: CredentialRef) =
        try {
            secrets.read(SecretRef(ref.value))
        } catch (_: SecretNotFound) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
}
