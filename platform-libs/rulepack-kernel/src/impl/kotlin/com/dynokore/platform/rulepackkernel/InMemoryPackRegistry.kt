package com.dynokore.platform.rulepackkernel

import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

/**
 * The reference [PackRegistry]: an immutable snapshot list swapped atomically
 * on [register] (CS-07: no shared mutable state), held in process memory
 * (INV-REQ-1195: "in its own schema or memory") — the shape every pack
 * holder that instead persists its registry (`PackRegistryMigration`) must
 * reproduce in its own store.
 */
class InMemoryPackRegistry : PackRegistry {
    private val versions = AtomicReference<List<PackVersion>>(emptyList())

    override fun register(version: PackVersion) {
        versions.updateAndGet { current ->
            val existing = current.firstOrNull { it.isSameLineage(version) }
            when {
                existing == null -> current + version

                existing.manifest == version.manifest -> current

                // at-least-once redelivery of RulePackStaged: a no-op
                else -> throw IllegalArgumentException(
                    "conflicting re-registration of an immutable pack version: ${version.packType} " +
                        "${version.version} for tenant ${version.tenant} already carries a different manifest " +
                        "(INV-ADR-263, INV-REQ-1089: a pack version never changes once registered)",
                )
            }
        }
    }

    override fun resolve(
        packType: PackType,
        tenant: TenantId?,
        resolutionDate: LocalDate,
    ): PackVersion? {
        val inForce =
            versions.get().filter { version ->
                version.packType == packType &&
                    !version.effectiveFrom.isAfter(resolutionDate) &&
                    (version.effectiveTo == null || resolutionDate.isBefore(version.effectiveTo)) &&
                    (version.tenant == null || version.tenant == tenant)
            }
        // INV-REQ-770: "returns the highest precedence, tenant pack over platform pack" — then, within one
        // precedence tier, the most recently activated version in force for this date.
        val tenantSpecific = inForce.filter { it.tenant != null }
        val pool = tenantSpecific.ifEmpty { inForce }
        return pool.maxByOrNull { it.effectiveFrom }
    }

    private fun PackVersion.isSameLineage(other: PackVersion) =
        packType == other.packType && tenant == other.tenant && version == other.version
}
