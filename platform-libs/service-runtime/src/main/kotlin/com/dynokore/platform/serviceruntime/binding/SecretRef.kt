package com.dynokore.platform.serviceruntime.binding

/**
 * Names a credential to read through [SecretStore] — a mount-relative identifier
 * (e.g. a mounted file's name), never the credential itself. Safe to log: it
 * carries no secret material (CS-24).
 */
@JvmInline
value class SecretRef(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "SecretRef must not be blank" }
        require("/../" !in "/$value/" && !value.startsWith("../") && value != "..") {
            "SecretRef must not traverse outside the binding: '$value'"
        }
    }

    override fun toString(): String = value
}
