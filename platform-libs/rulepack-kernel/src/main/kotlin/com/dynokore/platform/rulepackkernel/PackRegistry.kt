package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/**
 * Holds immutable, verified [PackVersion]s and resolves the one in force for a
 * business date (INV-REQ-770, INV-ADR-263, INV-BR-215).
 *
 * Framework-free (ARC-16): the `-api` half. `InMemoryPackRegistry` (`-impl`) is
 * the reference holder; a pack holder that persists its registry across
 * restarts uses the table [rulepackkernel's migration DDL][PackRegistryMigration]
 * generates, in its own schema (INV-REQ-1195: "in its own schema or memory").
 */
interface PackRegistry {
    /**
     * Adds an immutable pack version. Registering the exact same
     * ([PackType], [TenantId], [PackVersionLabel]) a second time with an
     * identical [PackManifest] is a no-op (an at-least-once redelivery of
     * `RulePackStaged`, INV-REQ-1195); registering it with a *different*
     * manifest is refused — a pack version never changes once registered
     * (INV-ADR-263, INV-REQ-1089).
     *
     * @throws IllegalArgumentException on a conflicting re-registration.
     */
    fun register(version: PackVersion)

    /**
     * `resolve(pack_type, entity, resolution_date)` (INV-REQ-770's own
     * behaviour text): the [PackVersion] of [packType] whose
     * `effective_from <= resolutionDate < effective_to` (open-ended when
     * `effective_to` is null), for [tenant] if a tenant-specific version
     * applies at that date, else the platform-wide version. Returns `null`
     * when none applies — a caller such as `rules-svc` maps that to its own
     * business error (RUL-002), which is not this port's concern.
     *
     * [resolutionDate] is always a transaction/business date supplied by the
     * caller, never the clock (ARC-13, INV-BR-215): this method never reads
     * `Clock` or `LocalDate.now()`.
     */
    fun resolve(
        packType: PackType,
        tenant: TenantId?,
        resolutionDate: LocalDate,
    ): PackVersion?
}
