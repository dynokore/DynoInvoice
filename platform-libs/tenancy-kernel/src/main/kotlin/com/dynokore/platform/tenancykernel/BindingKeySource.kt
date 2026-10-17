package com.dynokore.platform.tenancykernel

/**
 * The installation binding key that signs `app.binding_sig` (INV-REQ-854 b7, owner ruling INV-Q-4121), as the
 * migration runner reads it: the current key, and the previous key while a rotation is in flight. The key table holds
 * exactly these two, so a signature made with a key older than the previous one is refused.
 *
 * The interface names no secret store: a service-side adapter (service-runtime wiring) reads the keys from the
 * `SecretStore` and this module never depends on it (coordinator rulings INV-Q-5352, INV-Q-5354). An
 * implementation hands out a copy it does not keep using: the migration runner does not clear the arrays.
 */
interface BindingKeySource {
    /** The current key; null or empty when none is configured, which fails the migration (no blank key is written). */
    fun currentKey(): ByteArray?

    /** The previous key, or null when there is none (first installation, or a rotation that is finished). */
    fun previousKey(): ByteArray?
}
