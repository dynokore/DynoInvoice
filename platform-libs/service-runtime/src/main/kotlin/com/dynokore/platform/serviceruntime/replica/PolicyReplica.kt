package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.LocalDate
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

/** Where a variation-point value applies: the whole platform, one tenant, or one entity of a tenant. */
data class PolicyScope(val tenant: TenantId? = null, val entityRef: String? = null) {
    init {
        require(entityRef == null || tenant != null) { "an entity scope needs its tenant" }
    }

    /** This scope, then the wider ones: entity, tenant, platform. */
    fun widening(): List<PolicyScope> = buildList {
        add(this@PolicyScope)
        if (entityRef != null) add(PolicyScope(tenant))
        if (tenant != null) add(PLATFORM)
    }

    companion object {
        val PLATFORM = PolicyScope()
    }
}

enum class PolicySource { DEFAULT, PROFILE, OVERRIDE }

/**
 * The platform event `PolicyChanged` (INV-REQ-1241, INV-ADR-285). [value] is the
 * variation point's value as its owning service publishes it, opaque here.
 */
data class PolicyChanged(
    val scope: PolicyScope,
    val variationPointId: String,
    val value: String,
    val effectiveDate: LocalDate,
    val source: PolicySource,
    val relaxationAckRef: String?,
    val version: Long,
)

data class PolicyValue(
    val value: String,
    val effectiveDate: LocalDate,
    val source: PolicySource,
    val relaxationAckRef: String?,
)

/**
 * The policy replica (INV-REQ-1241): `PolicyChanged` from every owning service,
 * read locally by any other. Event-only: there is no fallback call, because no new
 * synchronous call is permitted (INV-REQ-1241 behaviour #5), so an unknown value is
 * simply absent and the reader applies its own default.
 *
 * Each scope and variation point keeps a schedule of values by effective date; a
 * read at a date takes the latest value effective on or before it, from the most
 * specific scope that has one (entity, then tenant, then platform). Redelivery and
 * reordering are harmless: per scope, variation point and effective date the
 * highest version wins.
 */
class PolicyReplica(clock: Clock) : ReplicaFeedSink<PolicyChanged> {
    private data class Slot(val scope: PolicyScope, val variationPointId: String)

    private data class Versioned(val value: PolicyValue, val version: Long)

    private val schedules = ConcurrentHashMap<Slot, TreeMap<LocalDate, Versioned>>()
    val tracker = ReplicaLagTracker(clock)

    override fun accept(event: PolicyChanged) {
        val schedule = schedules.computeIfAbsent(Slot(event.scope, event.variationPointId)) { TreeMap() }
        synchronized(schedule) {
            val held = schedule[event.effectiveDate]
            if (held == null || event.version > held.version) {
                schedule[event.effectiveDate] =
                    Versioned(
                        PolicyValue(event.value, event.effectiveDate, event.source, event.relaxationAckRef),
                        event.version,
                    )
            }
        }
        tracker.onApplied(event.version)
    }

    /** The value in force for [variationPointId] at [date] in [scope], or null if none is known. */
    fun valueAt(
        scope: PolicyScope,
        variationPointId: String,
        date: LocalDate,
    ): PolicyValue? = scope.widening().firstNotNullOfOrNull { candidate ->
        val schedule = schedules[Slot(candidate, variationPointId)] ?: return@firstNotNullOfOrNull null
        synchronized(schedule) { schedule.floorEntry(date)?.value?.value }
    }

    override fun observeHead(version: Long) = tracker.observeHead(version)

    override fun onDisconnected() = tracker.onDisconnected()

    fun lag(): Lag = tracker.lag()
}
