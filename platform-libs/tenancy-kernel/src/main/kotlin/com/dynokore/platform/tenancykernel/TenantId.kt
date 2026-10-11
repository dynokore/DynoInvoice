package com.dynokore.platform.tenancykernel

import java.util.UUID

/**
 * The tenant identifier every tenant-scoped query path carries (FR-G.5; CS-06:
 * identifiers are value classes, never a bare `String`/`UUID`).
 *
 * [toString] is exactly the canonical UUID text: this is the value
 * `TransactionRunner` binds with `set_config('app.tenant_id', …, true)` and the
 * RLS policy compares against (CS-19), so it must round-trip through
 * `::uuid` casts unchanged.
 */
@JvmInline
value class TenantId(val value: UUID) {
    override fun toString(): String = value.toString()

    /** True for the reserved [PLATFORM] id only. */
    val isPlatform: Boolean get() = value == PLATFORM_UUID

    companion object {
        private val PLATFORM_UUID: UUID = UUID(-1L, -1L)

        /**
         * The reserved platform tenant (Keel INV-Q-2927 option D; the max UUID
         * `ffffffff-ffff-ffff-ffff-ffffffffffff`, INV-Q-3012): the tenant under which
         * tenantless platform work (job runs across tenants or at installation level,
         * operator commands, installation-level policy, platform-wide pack readiness)
         * stores its rows, so every table keeps `tenant_id NOT NULL` with forced
         * row-level security (CS-19). Never provisioned (tenancy-svc refuses it), never
         * derivable from a token ([fromClaim] refuses it), and never bound through
         * [TenantContext.bind]: only [PlatformTenantBinding.bindPlatform] binds it.
         */
        val PLATFORM: TenantId = TenantId(PLATFORM_UUID)

        /**
         * Parses a stored or already-trusted tenant id; accepts [PLATFORM], which
         * platform rows hold in storage.
         */
        fun of(text: String): TenantId = TenantId(UUID.fromString(text))

        /**
         * Parses a tenant id taken from a user or machine token claim (INV-Q-2927,
         * INV-Q-3016): the reserved [PLATFORM] id is refused, so no token can carry
         * it into a binding.
         *
         * @throws IllegalArgumentException when [text] is not a UUID or is [PLATFORM].
         */
        fun fromClaim(text: String): TenantId {
            val tenant = of(text)
            require(!tenant.isPlatform) { "the reserved platform tenant cannot come from a token" }
            return tenant
        }
    }
}
