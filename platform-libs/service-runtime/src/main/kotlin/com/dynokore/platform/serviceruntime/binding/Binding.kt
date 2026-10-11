package com.dynokore.platform.serviceruntime.binding

/**
 * Marker for the closed set of deployment-infrastructure ports of SDS §12.1
 * (FR-H.1, ARC-11): the only way a service ever reaches something that differs
 * between the SaaS and private deployment forms. Adding a ninth binding is a
 * design change (INV-ADR-276) — this marker is what makes an addition visible in
 * review (a new `: Binding` here, or the equivalent in the module below that owns
 * it) rather than a deployment-form `if` sprinkled through service code.
 *
 * FR-H.1's eight names, and where each one is declared:
 * - [IdentityProvider], [PackSource], [TelemetryExport] and [MessageDelivery] — here,
 *   `service-runtime`, the module every service embeds.
 * - `EventBinding` — `outbox-and-queue` (implementation `PostgresEventBinding`, INV-REQ-1212), which
 *   cannot depend on this module; the composition root binds it (coordinator ruling INV-Q-3775).
 * - `SecretStore` — here (STK-29), for the vault every service needs at start-up
 *   (FR-AH.17) whether or not it owns an external dependency; a second,
 *   narrower `SecretStore` for adapter credentials specifically already ships in
 *   `adapter-runtime` (INV-T-2862 / T18), predating this module's own interfaces
 *   — flagged in this task's merge request for the coordinator to reconcile, not
 *   changed here (out of this task's declared scope, `service-runtime/` only).
 * - `LicenceCheck` — `tenancy-kernel` (INV-T-2861 / T17): entitlement is a tenancy
 *   concern, and `application` code depends on the port without depending on
 *   `service-runtime` for it.
 * - `ObjectStore` — `evidence-store` (INV-T-2863 / T19): the compliance-mode,
 *   no-delete object storage `evidence-svc` uses is a narrower contract than a
 *   general-purpose blob store, and lives with the module that defines it.
 *
 * Only the five declared in this module — [IdentityProvider], [PackSource],
 * [TelemetryExport], [MessageDelivery] and `SecretStore` — implement
 * this marker: a Konsist test (`ClosedBindingSetTest`) asserts nothing else in
 * `service-runtime` does.
 */
sealed interface Binding
