package com.dynokore.platform.serviceruntime.persistence

import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.Configuration
import java.time.Clock

/**
 * What the convenience layer needs of the service's one jOOQ `Configuration` (INV-T-4224): record-level optimistic
 * locking on, primary keys not updatable, and the audit-column RecordListener. service-runtime's wiring applies
 * [settings] and passes [recordListener] to `TenancyJooq.configure` or `install`; no other Configuration is made.
 */
object PersistenceConfiguration {
    /** Sets the layer's settings on [configuration], in place; call before `TenancyJooq.configure`. */
    fun settings(configuration: Configuration) {
        configuration
            .settings()
            .withExecuteWithOptimisticLocking(true)
            .withExecuteWithOptimisticLockingExcludeUnversioned(true)
            .withUpdateRecordVersion(true)
            .withUpdatablePrimaryKeys(false)
    }

    /** The audit-column RecordListener over the service [clock] and the transaction's bound actor. */
    fun recordListener(
        clock: Clock,
        tenants: TenantContext,
    ): AuditColumnRecordListener = AuditColumnRecordListener(clock, tenants)

    /**
     * Why [configuration] cannot back the convenience layer, or null: it must be tenancy-jooq's (tenant predicate and
     * statement check, INV-ADR-3968 items 4a and 4c), have the four settings of [settings], and carry the audit-column
     * RecordListener.
     */
    fun problem(configuration: Configuration): String? {
        val settings = configuration.settings()
        val tenancy = TenancyJooq.problem(configuration)
        return when {
            tenancy != null -> {
                "its jOOQ configuration is not tenancy-jooq's: $tenancy"
            }

            !(settings.isExecuteWithOptimisticLocking && settings.isExecuteWithOptimisticLockingExcludeUnversioned) -> {
                "executeWithOptimisticLocking and executeWithOptimisticLockingExcludeUnversioned are not both on"
            }

            !settings.isUpdateRecordVersion || settings.isUpdatablePrimaryKeys -> {
                "updateRecordVersion is not on or updatablePrimaryKeys is not off"
            }

            TenancyJooq.recordListeners(configuration).none { it is AuditColumnRecordListener } -> {
                "the audit-column RecordListener is not registered"
            }

            else -> {
                null
            }
        }
    }
}
