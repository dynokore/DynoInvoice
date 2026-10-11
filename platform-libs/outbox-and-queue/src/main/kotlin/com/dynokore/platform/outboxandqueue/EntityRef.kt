package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId

/** The registered entity an event concerns (FR-K.3, CloudEvents attribute `entityref`). */
@JvmInline
value class EntityRef(val value: String) {
    init {
        require(value.isNotBlank()) { "entity reference must not be blank" }
    }

    override fun toString(): String = value

    /** True for the reserved [PLATFORM] reference only. */
    val isPlatform: Boolean get() = value == PLATFORM_VALUE

    /** True for the reserved [TENANT_WIDE] reference only. */
    val isTenantWide: Boolean get() = value == TENANT_WIDE_VALUE

    companion object {
        private const val PLATFORM_VALUE = "ffffffff-ffff-ffff-ffff-ffffffffffff"
        private const val TENANT_WIDE_VALUE = "00000000-0000-0000-0000-000000000000"

        /**
         * The entity of a tenantless platform event (Keel INV-Q-3014, symmetric with
         * tenancy-kernel's `TenantId.PLATFORM`, INV-Q-2927 option D): stored in the outbox's
         * `entity_ref` under the platform tenant, left out of the envelope on the wire, and mapped
         * back from an absent `entityref` on consume. Always paired with `TenantId.PLATFORM`.
         */
        val PLATFORM: EntityRef = EntityRef(PLATFORM_VALUE)

        /**
         * The entity of a platform event that concerns a whole tenant or group and no registered entity
         * (Keel INV-Q-3165, shaped like [PLATFORM]): never issued, distinct from [PLATFORM], always paired
         * with a real tenant. Stored in the outbox's `entity_ref` (NOT NULL, forced RLS unchanged); a
         * platform event type leaves `entityref` out of the envelope and keeps `tenantid`, and a consumer
         * maps "tenantid present, entityref absent" back to it. A domain event cannot carry it (FR-K.3).
         */
        val TENANT_WIDE: EntityRef = EntityRef(TENANT_WIDE_VALUE)
    }
}

internal const val PLATFORM_PAIR =
    "the platform tenant and the platform entity go together; the tenant-wide entity needs a real tenant " +
        "(INV-Q-2927 option D, INV-Q-3014, INV-Q-3165)"

/** The pairing rule: PLATFORM only with the platform tenant, TENANT_WIDE only with a real tenant. */
internal fun pairingHolds(tenant: TenantId, entity: EntityRef): Boolean {
    if (tenant.isPlatform) return entity.isPlatform
    return !entity.isPlatform
}
