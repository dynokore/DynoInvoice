package com.dynokore.platform.evidencestore

/**
 * A whole-segment key prefix for [ObjectStore.bytesUnder] (CS-06: a value class, never a bare `String`).
 * The trailing `/` is required so that `p/T/E1/` can never also match `p/T/E10/`.
 */
@JvmInline
value class ObjectKeyPrefix(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "ObjectKeyPrefix must not be blank" }
        require(value.endsWith("/")) { "ObjectKeyPrefix must end with '/' (a whole key segment)" }
    }
}
