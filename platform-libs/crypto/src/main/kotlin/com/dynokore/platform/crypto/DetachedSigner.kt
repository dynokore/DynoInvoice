package com.dynokore.platform.crypto

/**
 * Makes a detached CMS signature (`SignedData` without encapsulated content, STK-13) over content
 * that travels separately. The `crypto` (impl) half provides it.
 */
fun interface DetachedSigner {
    /** The DER-encoded detached CMS `SignedData` over [content], signed by [key] and carrying its certificate. */
    fun sign(
        content: ByteArray,
        key: SigningKey,
    ): ByteArray
}
