package com.dynokore.platform.evidencestore

/** The key an evidence blob is stored under in [ObjectStore] (CS-06: a value class, never a bare `String`). */
@JvmInline
value class ObjectKey(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "ObjectKey must not be blank" }
    }
}
