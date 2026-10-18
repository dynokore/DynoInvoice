package com.dynokore.platform.serviceruntime.binding

import java.util.concurrent.ConcurrentHashMap

/** A [SecretStore] over a mutable map: a test rotates a secret with [put] or [remove], as a mounted file would change. */
class FakeSecretStore : SecretStore {
    private val values = ConcurrentHashMap<SecretRef, String>()

    fun put(
        ref: SecretRef,
        value: String,
    ) {
        values[ref] = value
    }

    fun remove(ref: SecretRef) {
        values.remove(ref)
    }

    override fun read(ref: SecretRef): Secret = Secret(values[ref] ?: throw SecretNotFound(ref))
}
