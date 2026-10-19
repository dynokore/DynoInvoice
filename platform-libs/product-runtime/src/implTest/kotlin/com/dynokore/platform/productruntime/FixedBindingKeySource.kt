package com.dynokore.platform.productruntime

import com.dynokore.platform.serviceruntime.binding.InstallationBindingKey
import com.dynokore.platform.tenancykernel.BindingKeySource

/**
 * An in-memory binding key for the compositions that load service-runtime's auto-configuration (INV-T-4156): the
 * installation binding key is required at start-up and no SecretStore holds it here. Register it with
 * `.withBean(InstallationBindingKey::class.java, { FixedBindingKeySource.installationBindingKey() })`.
 */
internal object FixedBindingKeySource : BindingKeySource {
    override fun currentKey(): ByteArray = ByteArray(32) { 7 }

    override fun previousKey(): ByteArray? = null

    fun installationBindingKey(): InstallationBindingKey = InstallationBindingKey(this)
}
