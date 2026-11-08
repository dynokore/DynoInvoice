package com.dynokore.gst.tenancy.application.metering

import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The plan limits tenancy-svc compares the counters with (INV-REQ-879). */
enum class LimitKind { DOCUMENTS_PER_MONTH, ENTITIES }

data class Entitlement(
    val planVersion: String,
    val maxEntities: Int,
    val maxDocumentsPerMonth: Int,
)

data class EntityCounters(
    val entityRef: String,
    val documents: Int,
    val resubmissions: Int,
    val archiveVolumeBytes: Long?,
)

data class UsageCounters(
    val period: String,
    val documents: Int,
    val resubmissions: Int,
    val activeEntities: Int,
    val entities: List<EntityCounters>,
    val archiveVolumeBytes: Long?,
)

/** The metering data of the bound tenant; every call runs in the caller's transaction. */
interface MeteringStore {
    /** Adds one document (version 1) or one resubmission (above 1) when [entityRef] is an entity of the tenant. */
    fun count(
        entityRef: String,
        period: String,
        resubmission: Boolean,
    ): Boolean

    fun documentsIn(period: String): Int

    fun resubmissionsIn(period: String): Int

    fun activeEntities(): Int

    /** The entities with a counter in [period], plus the entities with a measured volume; [only] narrows to one. */
    fun entities(
        period: String,
        only: String?,
    ): List<EntityCounters>

    /** Keeps the value with the latest [measuredAt] per entity ([entityRef] null: the tenant-wide value). */
    fun keepVolume(
        entityRef: String?,
        bytes: Long,
        measuredAt: Instant,
    )

    /** The tenant total of the latest values, or null when no event of either kind was received. */
    fun totalVolume(): Long?

    fun putEntitlement(entitlement: Entitlement)

    fun entitlement(kind: LimitKind): Pair<String, Int>?

    /** True once per (kind, period): records the raise and tells the caller to publish. */
    fun markRaised(
        kind: LimitKind,
        period: String,
    ): Boolean
}

/** Publishes PlanLimitExceeded in the caller's transaction (TPL-05). */
fun interface PlanLimitPublisher {
    fun exceeded(
        kind: LimitKind,
        period: String?,
        limit: Int,
        observed: Int,
    )
}

/**
 * Metering and plan limits (INV-REQ-878, INV-REQ-879, INV-Q-9815 A, INV-Q-9817 A). Exceeding a limit publishes an event
 * and nothing else: no operation is refused or delayed here.
 */
class Metering(
    private val tx: TransactionRunner,
    private val store: MeteringStore,
    private val publisher: PlanLimitPublisher?,
    private val clock: () -> Instant,
) {
    /** A SubmissionAcknowledged: counts it (version 1 a document, above 1 a resubmission) and checks the limits. */
    fun acknowledged(
        entityRef: String,
        timestamp: Instant,
        version: Int,
    ) {
        val period = periodOf(timestamp)
        if (store.count(entityRef, period, resubmission = version > 1)) checkLimits(period)
    }

    fun measured(
        entityRef: String?,
        bytes: Long,
        measuredAt: Instant,
    ) = store.keepVolume(entityRef, bytes, measuredAt)

    fun record(entitlement: Entitlement): Entitlement =
        tx.inTransaction {
            store.putEntitlement(entitlement)
            checkLimits(periodOf(clock()))
            entitlement
        }

    fun usage(
        period: String?,
        only: String?,
    ): UsageCounters =
        tx.inTransaction {
            val month = period ?: periodOf(clock())
            val items = store.entities(month, only)
            UsageCounters(
                month,
                store.documentsIn(month),
                store.resubmissionsIn(month),
                store.activeEntities(),
                items,
                store.totalVolume(),
            )
        }

    /** The first excess of a stored limit publishes once per (kind, period); no stored limit compares nothing. */
    fun checkLimits(period: String) {
        // no binding: nothing is compared or marked (see the task gap on the entity)
        val publisher = publisher ?: return
        store.entitlement(LimitKind.DOCUMENTS_PER_MONTH)?.let { (_, limit) ->
            val observed = store.documentsIn(period)
            if (observed > limit && store.markRaised(LimitKind.DOCUMENTS_PER_MONTH, period)) {
                publisher.exceeded(LimitKind.DOCUMENTS_PER_MONTH, period, limit, observed)
            }
        }
        store.entitlement(LimitKind.ENTITIES)?.let { (_, limit) ->
            val observed = store.activeEntities()
            if (observed > limit && store.markRaised(LimitKind.ENTITIES, "")) {
                publisher.exceeded(LimitKind.ENTITIES, null, limit, observed)
            }
        }
    }

    companion object {
        private val MONTH = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC)

        /** The UTC calendar month of an instant. */
        fun periodOf(instant: Instant): String = MONTH.format(instant)
    }
}
