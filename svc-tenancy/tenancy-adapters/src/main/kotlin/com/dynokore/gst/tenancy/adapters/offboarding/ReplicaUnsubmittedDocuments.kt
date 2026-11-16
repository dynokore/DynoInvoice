package com.dynokore.gst.tenancy.adapters.offboarding

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.application.offboarding.UnavailableInput
import com.dynokore.gst.tenancy.application.offboarding.UnsubmittedDocuments
import com.dynokore.gst.tenancy.domain.OffboardingScope
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The tenant-wide row of the read model for the bound tenant, or null when none exists (INV-T-22885). */
fun interface UnsubmittedCountSource {
    fun read(): StoredUnsubmittedCount?
}

/** Whether the bound tenant has an entity row of any status in the store of tenancy-svc. */
fun interface TenantEntityCheck {
    fun hasAnyEntity(): Boolean
}

/** The entity rows of the bound tenant (forced row-level security), whatever the lifecycle state or retirement. */
class JooqTenantEntityCheck(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : TenantEntityCheck {
    override fun hasAnyEntity(): Boolean = dsl.fetchExists(ENTITY, ENTITY.TENANT_ID.eq(tenants.current().value))
}

/**
 * The pure currency rule (INV-AC-22872): the count received at [receivedAt] is current at [now] exactly when the age
 * is not negative and not greater than [maxAge].
 */
fun isCurrent(
    receivedAt: Instant,
    now: Instant,
    maxAge: Duration,
): Boolean {
    val age = Duration.between(receivedAt, now)
    return !age.isNegative && age <= maxAge
}

/**
 * The documents-count port over the read model of `UnsubmittedDocumentCountMeasured` (INV-REQ-956). A count is
 * returned only when it is current; a tenant with no entity row of any status has no document and counts 0. Every
 * other case, including any failing read, throws [UnavailableInput] so the offboarding is refused 422 POL-014; a
 * failure is never a zero. The entity check is made first, so a failing check refuses even when a row exists.
 */
class ReplicaUnsubmittedDocuments(
    private val replica: UnsubmittedCountSource,
    private val entities: TenantEntityCheck,
    private val clock: Clock,
    private val maxAge: Duration,
) : UnsubmittedDocuments {
    override fun count(
        scope: OffboardingScope,
        entityRef: String?,
    ): Int {
        val hasEntity = guarded { entities.hasAnyEntity() }
        val stored = guarded { replica.read() }
        if (stored == null) {
            if (hasEntity) throw unavailable(null)
            return 0
        }
        if (!isCurrent(stored.receivedAt, clock.instant(), maxAge)) throw unavailable(null)
        val measurement = stored.measurement
        return when (scope) {
            OffboardingScope.TENANT -> measurement.total
            OffboardingScope.ENTITY -> measurement.entities.firstOrNull { it.entityRef == entityRef }?.count ?: 0
        }
    }

    // Fail closed (NIST SP 800-53 SC-24): whatever a read throws becomes the refusal, never a zero.
    @Suppress("TooGenericExceptionCaught")
    private fun <T> guarded(read: () -> T): T =
        try {
            read()
        } catch (e: Exception) {
            throw unavailable(e)
        }

    private fun unavailable(cause: Exception?): UnavailableInput =
        UnavailableInput(INPUT).also { if (cause != null) it.initCause(cause) }

    companion object {
        const val INPUT = "unsubmitted-documents-count"
    }
}
