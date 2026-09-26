package com.dynokore.platform.adapterruntime.fixtures

import com.dynokore.platform.adapterruntime.Secret
import com.dynokore.platform.adapterruntime.SecretNotFound
import com.dynokore.platform.adapterruntime.SecretRef
import com.dynokore.platform.adapterruntime.SecretStore

/**
 * A test fixture for [SecretStore] (CS-43: "prefer a fake from the port's test
 * fixtures to a mock"). Every fake and every real implementation of this port runs
 * the same `SecretStoreContractTest`.
 */
class FakeSecretStore(seed: Map<String, String> = emptyMap()) : SecretStore {
    private val values = seed.mapKeys { (name, _) -> SecretRef(name) }.toMutableMap()

    fun put(
        ref: SecretRef,
        value: String,
    ) {
        values[ref] = value
    }

    override fun read(ref: SecretRef): Secret = values[ref]?.let(::Secret) ?: throw SecretNotFound(ref)
}
