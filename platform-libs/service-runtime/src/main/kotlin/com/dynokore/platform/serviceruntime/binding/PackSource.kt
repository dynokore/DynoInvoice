package com.dynokore.platform.serviceruntime.binding

/**
 * Fetches a signed rule-pack artefact's raw bytes from the control-plane feed
 * (SDS §12.1; `rulepack-kernel`'s `PackVerifier` KDoc: "`rules-svc`, the sole
 * `PackSource` client"). `rules-svc` is this binding's only caller: it fetches
 * through this port, then verifies, compiles and stages the artefact with
 * `rulepack-kernel`'s own `PackVerifier`/`PackRegistry` (INV-T-2866 / T22) — this
 * port never verifies or interprets what it fetches, so it carries no dependency
 * of its own on `rulepack-kernel` (ARC-16: service-runtime, first in the §18.9 L1
 * order, uses no other L1 module).
 *
 * Declared here as one of the closed set of SDS §12.1 bindings (FR-H.1); no
 * implementation ships with this task (done-when, INV-T-2871 / T27) — one lands
 * with the task that gives `rules-svc` its import client.
 */
fun interface PackSource : Binding {
    /** The artefact's raw signed bytes, unverified — the caller's job (`PackVerifier`). */
    fun fetch(reference: PackSourceRef): ByteArray
}

/** Names an artefact at the control-plane feed — opaque to this binding, meaningful only to the feed. */
@JvmInline
value class PackSourceRef(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "PackSourceRef must not be blank" }
    }
}
