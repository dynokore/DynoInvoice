package com.dynokore.platform.adapterruntime

/**
 * A credential value read through [SecretStore] (CS-24). Never a `data class` and
 * never prints its raw value: `toString()` (and so any string template, log argument
 * or exception message built from it) is redacted. The only way to the raw value is
 * the explicit [reveal] call, which a caller uses only to hand the value straight to
 * the external system it authenticates against — never to log, persist or embed it
 * elsewhere.
 */
@JvmInline
value class Secret(private val value: String) {
    /** The raw credential. Callers must not log, persist or otherwise surface this (CS-24). */
    fun reveal(): String = value

    override fun toString(): String = "Secret(***)"
}
