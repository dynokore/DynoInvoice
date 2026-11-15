package com.dynokore.gst.submission.application.offboarding

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Instant

/**
 * Port: per entity, the number of in-scope documents of a tenant that are unsubmitted by the readiness verdict's
 * definition (INV-REQ-956, INV-REQ-71). Entities with 0 are omitted. A failed read throws; it never answers an empty
 * map (fail closed, OWASP ASVS V1).
 */
fun interface UnsubmittedCountsByEntity {
    fun counts(tenant: TenantId): Map<String, Int>
}

/** Port: the tenants whose unsubmitted count is measured (a register entry or an entity node exists). */
fun interface MeasuredTenantSource {
    fun tenants(): List<TenantId>
}

/** The unsubmitted count of one entity. */
data class EntityCount(
    val entityRef: String,
    val count: Int,
)

/** The measurement of a tenant: [total] is the sum of the [entities] counts, ordered by entity reference. */
data class UnsubmittedCountMeasurement(
    val measuredAt: Instant,
    val total: Int,
    val entities: List<EntityCount>,
)

/**
 * Measures the unsubmitted in-scope documents of a tenant by entity (INV-REQ-956). A workload read: no permission key
 * is checked here, and a source exception propagates unchanged.
 */
class MeasureUnsubmittedCount(
    private val counts: UnsubmittedCountsByEntity,
    private val clock: Clock,
) {
    fun execute(tenant: TenantId): UnsubmittedCountMeasurement {
        val entities = counts.counts(tenant).map { EntityCount(it.key, it.value) }.sortedBy { it.entityRef }
        return UnsubmittedCountMeasurement(clock.instant(), entities.sumOf { it.count }, entities)
    }
}
