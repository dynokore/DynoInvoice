package com.dynokore.platform.adapterruntime

/**
 * Reads a credential for an external adapter (STK-29, CS-24). Every credential an
 * adapter uses comes through this port; it is never read directly from a file,
 * environment variable or configuration property by adapter code.
 *
 * STK-29: the binding reads mounted files only, populated by External Secrets
 * Operator or a Kubernetes Secret depending on deployment form; rotation happens by
 * file watch, without restart. A conforming implementation never caches a value
 * across calls for exactly this reason.
 *
 * Services bind this port by delegating to `service-runtime`'s `SecretStore`
 * binding (STK-60 L1 module policy: each L1 declares its ports, binding
 * implementations live only in service-runtime).
 */
interface SecretStore {
    /** @throws SecretNotFound when no credential is bound at [ref]. */
    fun read(ref: SecretRef): Secret
}

/** Thrown by [SecretStore.read] when [ref] names no bound credential. */
class SecretNotFound(val ref: SecretRef) : NoSuchElementException("no secret bound at '$ref'")
