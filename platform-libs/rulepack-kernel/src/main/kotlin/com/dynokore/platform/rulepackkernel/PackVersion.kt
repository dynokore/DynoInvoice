package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancykernel.TenantId

/**
 * One immutable, verified pack version held in a [PackRegistry] (INV-ADR-263:
 * "resolution by transaction date", never re-written — supersession only adds
 * a later version and sets the prior one's `effective_to`, INV-REQ-1089).
 *
 * [tenant] is `null` for a platform-wide pack (packs.yaml: every pack type this
 * module resolves today is `tenant_overridable: 'No'`) and set only for a
 * tenant-specific override, which [PackRegistry.resolve] prefers over a
 * platform pack for the same [PackType] and date (INV-REQ-770: "returns the
 * highest precedence, tenant pack over platform pack") — the one place this
 * kernel touches `tenancy-kernel` (INV-ADR-2774, INV-MOD-2373 "uses
 * tenancy-kernel"), reusing its [TenantId] rather than declaring a second,
 * incompatible tenant identifier.
 *
 * Every other field is read from [manifest] rather than duplicated here: the
 * manifest is the one place a pack's identity, type, version and effective
 * dates are recorded (STK-13).
 */
data class PackVersion(
    val manifest: PackManifest,
    val tenant: TenantId? = null,
) {
    val packId: PackId get() = manifest.packId
    val packType: PackType get() = manifest.packType
    val version: PackVersionLabel get() = manifest.version
    val effectiveFrom get() = manifest.effectiveFrom
    val effectiveTo get() = manifest.effectiveTo
}
