package com.dynokore.platform.serviceruntime.binding

/**
 * The platform event log's transport (SDS §12.1; TPL-16's `values-private.yaml`
 * fragment: `bindings.eventBinding: postgres`): how the outbox relay and the
 * inbox actually move an envelope between services, once `outbox-and-queue`
 * (STK-60's second L1 module) stamps it (FR-K.3, FR-M.11). The two forms may
 * genuinely need different transports (e.g. `LISTEN`/`NOTIFY` over PostgreSQL in
 * private versus a managed broker in SaaS) — the "one implementation per form"
 * half of FR-H.1's closed-set behaviour, unlike [SecretStore] or
 * [TelemetryExport].
 *
 * Declared here as one of the closed set of SDS §12.1 bindings (FR-H.1); no
 * implementation ships with this task (done-when, INV-T-2871 / T27) —
 * `outbox-and-queue` does not have its own module code yet either (settings.gradle.kts:
 * still README-only). One lands with whichever task gives that module its
 * envelope and relay.
 */
interface EventBinding : Binding {
    /** Publishes [payload] under [key] on [topic] — at-least-once, ordered per [key] (FR-J.4). */
    fun publish(
        topic: String,
        key: String,
        payload: ByteArray,
    )
}
