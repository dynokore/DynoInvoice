package com.dynokore.platform.outboxandqueue

/** The registered entity an event concerns (FR-K.3, CloudEvents attribute `entityref`). */
@JvmInline
value class EntityRef(val value: String) {
    init {
        require(value.isNotBlank()) { "entity reference must not be blank" }
    }

    override fun toString(): String = value

    /** True for the reserved [PLATFORM] reference only. */
    val isPlatform: Boolean get() = value == PLATFORM_VALUE

    companion object {
        private const val PLATFORM_VALUE = "ffffffff-ffff-ffff-ffff-ffffffffffff"

        /**
         * The entity of a tenantless platform event (Keel INV-Q-3014, symmetric with
         * tenancy-kernel's `TenantId.PLATFORM`, INV-Q-2927 option D): stored in the outbox's
         * `entity_ref` under the platform tenant, left out of the envelope on the wire, and mapped
         * back from an absent `entityref` on consume. Always paired with `TenantId.PLATFORM`.
         */
        val PLATFORM: EntityRef = EntityRef(PLATFORM_VALUE)
    }
}

internal const val PLATFORM_PAIR =
    "the platform tenant and the platform entity go together (INV-Q-2927 option D, INV-Q-3014)"
