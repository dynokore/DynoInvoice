package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Instant

/** The status of a break-glass session this replica holds. */
enum class BreakGlassStatus { OPEN, SEALED }

/**
 * The decoded platform event `BreakGlassSessionChanged` (INV-Q-9119 A). [status] is the wire text, kept as received:
 * the replica ignores a value other than `OPEN` and `SEALED` and counts it.
 */
data class BreakGlassSessionChanged(
    val unlockRef: String,
    val tenant: TenantId,
    val status: String,
    val expiresAt: Instant,
    val approvedBy: List<String>,
)

/** One break-glass session as held: the approvers are the two people who unlocked it. */
data class BreakGlassSession(
    val unlockRef: String,
    val tenant: TenantId,
    val status: BreakGlassStatus,
    val expiresAt: Instant,
    val approvedBy: List<String>,
)

/**
 * The open session whose approver [actor] is, for [tenant], at [at]; a service may replace the view (access-svc does,
 * INV-Q-9298 B).
 */
fun interface BreakGlassSessionView {
    fun restrictedSession(
        tenant: TenantId,
        actor: String,
        at: Instant,
    ): BreakGlassSession?
}

/** The break-glass actions a marked operation reports (INV-Q-9119 A). */
enum class BreakGlassAction { VIEW, READINESS, DRAIN, REPLAY }

/**
 * Reports one marked action of an approver of an open session, so the alert and the audit entry exist before the
 * action runs (INV-REQ-897). A service supplies one; a failure is fail closed.
 */
fun interface BreakGlassActionReporter {
    fun report(
        unlockRef: String,
        action: BreakGlassAction,
        target: String,
        occurredAt: Instant,
    )
}

/** Counts the break-glass events a replica ignores and the requests the mode refuses; the impl half records them. */
fun interface BreakGlassCounter {
    fun increment(name: String)

    companion object {
        const val EVENT_IGNORED = "break_glass_event_ignored_total"
        const val REFUSED = "break_glass_refused_total"
        val NONE = BreakGlassCounter { }
    }
}

/**
 * The break-glass sessions replicated by `BreakGlassSessionChanged` (INV-Q-9119 A), per (tenant, unlockRef):
 *  - OPEN stores the session when none is held and changes nothing when one is held;
 *  - SEALED stores or replaces it as sealed, and a sealed session is never reopened by a later OPEN;
 *  - any other status is ignored, the held state is kept and `break_glass_event_ignored_total` rises;
 *  - an entry at or after its `expiresAt` is dropped on the next call, so expiry is checked locally.
 */
class BreakGlassSessionReplica(
    private val clock: Clock,
    private val counter: BreakGlassCounter = BreakGlassCounter.NONE,
) : BreakGlassSessionView {
    private val held = mutableMapOf<Pair<TenantId, String>, BreakGlassSession>()

    @Synchronized
    fun accept(event: BreakGlassSessionChanged) {
        dropExpired()
        val status = BreakGlassStatus.entries.firstOrNull { it.name == event.status }
        if (status == null) {
            counter.increment(BreakGlassCounter.EVENT_IGNORED)
            return
        }
        val key = event.tenant to event.unlockRef
        if (status == BreakGlassStatus.OPEN && key in held) return
        held[key] = BreakGlassSession(event.unlockRef, event.tenant, status, event.expiresAt, event.approvedBy.toList())
    }

    /** The session held for ([tenant], [unlockRef]) after expired ones are dropped, or null. */
    @Synchronized
    fun held(
        tenant: TenantId,
        unlockRef: String,
    ): BreakGlassSession? {
        dropExpired()
        return held[tenant to unlockRef]
    }

    @Synchronized
    override fun restrictedSession(
        tenant: TenantId,
        actor: String,
        at: Instant,
    ): BreakGlassSession? {
        dropExpired()
        return held.values
            .filter {
                it.tenant == tenant && it.status == BreakGlassStatus.OPEN && at.isBefore(it.expiresAt) &&
                    actor in it.approvedBy
            }.sortedWith(compareByDescending<BreakGlassSession> { it.expiresAt }.thenBy { it.unlockRef })
            .firstOrNull()
    }

    private fun dropExpired() {
        val now = clock.instant()
        held.values.removeIf { !it.expiresAt.isAfter(now) }
    }
}
