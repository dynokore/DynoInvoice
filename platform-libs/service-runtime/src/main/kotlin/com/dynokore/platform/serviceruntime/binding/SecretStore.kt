package com.dynokore.platform.serviceruntime.binding

/**
 * Reads a credential from the vault (STK-29, CS-24, SDS §12.1). Every service
 * embeds `service-runtime`, so this is the one binding every service — not only
 * an owner of an external dependency (FR-AH.20, §36.7) — uses directly: for its
 * own database role's password (TPL-16: "through SecretStore") and for the vault
 * reachability check every cold start needs (FR-AH.17, §36.7 "a cold start while
 * the vault is down fails visibly").
 *
 * STK-29: the binding reads mounted files only, populated by External Secrets
 * Operator or a Kubernetes Secret depending on deployment form — one code path
 * for both; rotation happens by file watch, without restart. A conforming
 * implementation never caches a value across calls for exactly this reason — see
 * [MountedFileSecretStore][com.dynokore.platform.serviceruntime.binding.MountedFileSecretStore].
 *
 * "A service can read only its own secret paths" (FR-AH.20, this task's
 * done-when) is enforced twice over: structurally, [SecretRef] refuses to name
 * anything outside the binding's mount root; and operationally, the mount root
 * itself is this service's own volume — the Helm chart mounts each service only
 * the secret paths its own `values.yaml` entry names, so one service's pod never
 * has another's secret directory mounted into it at all.
 */
interface SecretStore : Binding {
    /** @throws SecretNotFound when no credential is bound at [ref]. */
    fun read(ref: SecretRef): Secret
}

/** Thrown by [SecretStore.read] when [ref] names no bound credential. */
class SecretNotFound(val ref: SecretRef) : NoSuchElementException("no secret bound at '$ref'")
