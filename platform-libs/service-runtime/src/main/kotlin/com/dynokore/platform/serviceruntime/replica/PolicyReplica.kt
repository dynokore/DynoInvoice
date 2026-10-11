package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.ScopeRef
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

enum class PolicySource { DEFAULT, PROFILE, OVERRIDE }

/**
 * The platform event `PolicyChanged` (INV-REQ-1241, INV-ADR-285). [value] is the
 * variation point's value as its owning service publishes it, opaque here. [scope] is
 * config-resolver's [PolicyScope] (INV-REQ-1242 behaviour #1): the event sets the value at
 * the scope's most specific level. [topic] and [offset] are where the event sits in the
 * event log, the producer's topic and the record's offset in it (INV-REQ-1205); they order
 * the events and measure lag, and are not part of the payload.
 */
data class PolicyChanged(
    val scope: PolicyScope,
    val variationPointId: String,
    val value: String,
    val effectiveDate: LocalDate,
    val source: PolicySource,
    val relaxationAckRef: String?,
    val topic: String,
    val offset: Long,
)

data class PolicyValue(
    val value: String,
    val effectiveDate: LocalDate,
    val source: PolicySource,
    val relaxationAckRef: String?,
)

/**
 * What a feed calls on the [PolicyReplica]: [ReplicaFeedSink] with every position given per
 * producer topic, because event-log offsets are only ordered within one topic.
 */
interface PolicyFeedSink {
    fun accept(event: PolicyChanged)

    /** A heartbeat: [topic]'s log is at [offset] now. */
    fun observeHead(
        topic: String,
        offset: Long,
    )

    /** The feed has delivered [topic] up to [offset] as a full sync or snapshot. */
    fun onSnapshotComplete(
        topic: String,
        offset: Long,
    )

    /** The feed of [topic] has dropped. */
    fun onDisconnected(topic: String)
}

/**
 * The policy replica (INV-REQ-1241): `PolicyChanged` from every owning service,
 * read locally by any other. Event-only: there is no fallback call, because no new
 * synchronous call is permitted (INV-REQ-1241 behaviour #5), so an unknown value is
 * simply absent and the reader applies its own default.
 *
 * Scopes are config-resolver's (INV-REQ-1242): a read takes the [PolicyScope.chain] of the
 * caller (client entity, Access Point connection, feed, entity, group, tenant, installation),
 * so a value set per AP connection is found. Each scope level and variation point keeps a
 * schedule of values by effective date; a read at a date takes the latest value effective on
 * or before it, from the most specific level that has one. Scope ids are taken as unique
 * within their tenant.
 *
 * Ordering is by event-log offset: per scope level, variation point and effective date a
 * later offset of the same topic wins, so redelivery and reordering are harmless. A variation
 * point has one owning service and so one topic (INV-REQ-1241); if two topics ever set the same
 * slot, the one applied last wins, since offsets of different topics are not comparable.
 * Lag is measured per producer topic and the replica's lag is the worst of them: unknown
 * until every topic seen (or listed in [topics]) has reported its snapshot.
 */
class PolicyReplica(
    private val clock: Clock,
    topics: Set<String> = emptySet(),
) : PolicyFeedSink {
    private data class Slot(val tenant: TenantId?, val ref: ScopeRef, val variationPointId: String)

    private data class Versioned(val value: PolicyValue, val topic: String, val offset: Long)

    private val schedules = ConcurrentHashMap<Slot, TreeMap<LocalDate, Versioned>>()
    private val trackers = ConcurrentHashMap<String, ReplicaLagTracker>()

    init {
        topics.forEach { tracker(it) }
    }

    private fun tracker(topic: String): ReplicaLagTracker = trackers.computeIfAbsent(topic) { ReplicaLagTracker(clock) }

    /** A level of installation is platform-wide, so it carries no tenant. */
    private fun slot(
        scope: PolicyScope,
        ref: ScopeRef,
        variationPointId: String,
    ) = Slot(if (ref.level == ScopeLevel.INSTALLATION) null else scope.tenant, ref, variationPointId)

    override fun accept(event: PolicyChanged) {
        val own = event.scope.chain().first()
        val schedule = schedules.computeIfAbsent(slot(event.scope, own, event.variationPointId)) { TreeMap() }
        synchronized(schedule) {
            val held = schedule[event.effectiveDate]
            if (held == null || held.topic != event.topic || event.offset > held.offset) {
                schedule[event.effectiveDate] =
                    Versioned(
                        PolicyValue(event.value, event.effectiveDate, event.source, event.relaxationAckRef),
                        event.topic,
                        event.offset,
                    )
            }
        }
        tracker(event.topic).onApplied(event.offset)
    }

    /** The value in force for [variationPointId] at [date] in [scope], or null if none is known. */
    fun valueAt(
        scope: PolicyScope,
        variationPointId: String,
        date: LocalDate,
    ): PolicyValue? = scope.chain().firstNotNullOfOrNull { ref ->
        val schedule = schedules[slot(scope, ref, variationPointId)] ?: return@firstNotNullOfOrNull null
        synchronized(schedule) { schedule.floorEntry(date)?.value?.value }
    }

    override fun observeHead(
        topic: String,
        offset: Long,
    ) = tracker(topic).observeHead(offset)

    override fun onSnapshotComplete(
        topic: String,
        offset: Long,
    ) = tracker(topic).onSnapshotComplete(offset)

    override fun onDisconnected(topic: String) = tracker(topic).onDisconnected()

    /** The lag of one producer [topic]; [Lag.Unknown] if it has never been heard from. */
    fun lag(topic: String): Lag = trackers[topic]?.lag() ?: Lag.Unknown

    /** The worst lag over the topics known: unknown if none, or if any is unknown. */
    fun lag(): Lag {
        val lags = trackers.values.map { it.lag() }
        if (lags.isEmpty() || lags.any { it is Lag.Unknown }) return Lag.Unknown
        return Lag.Measured(lags.maxOf { (it as Lag.Measured).duration }.coerceAtLeast(Duration.ZERO))
    }

    /** The highest offset applied for [topic], or -1 before the first. */
    fun appliedOffset(topic: String): Long = trackers[topic]?.appliedVersion() ?: -1L
}
