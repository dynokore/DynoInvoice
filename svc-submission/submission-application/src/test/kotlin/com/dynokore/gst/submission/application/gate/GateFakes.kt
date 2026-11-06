package com.dynokore.gst.submission.application.gate

import com.dynokore.gst.submission.domain.gate.GateMode
import com.dynokore.gst.submission.domain.gate.LatestCheck
import com.dynokore.gst.submission.domain.gate.OverrideStatus
import com.dynokore.gst.submission.domain.gate.ReleaseOutcome
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

class FakeAuthority(
    private val holders: Set<String>,
) : OverrideAuthority {
    val asked = CopyOnWriteArrayList<Triple<String, String, String>>()

    override fun holds(
        userId: String,
        permission: String,
        entityRef: String,
    ): Boolean {
        asked += Triple(userId, permission, entityRef)
        return permission == VALIDATION_OVERRIDE && userId in holders
    }
}

/** `SECOND_APPROVER_ALWAYS` (or a scripted answer) at the fake of `decideOverrideRelease`. */
class FakeDecisions(
    private val scripted: ReleaseOutcome? = null,
) : ReleaseDecisions {
    val calls = CopyOnWriteArrayList<Triple<String, String, List<String>>>()

    override fun decide(
        entityRef: String,
        errorCode: String,
        approverRef: String,
        priorApproverRefs: List<String>,
    ): ReleaseOutcome {
        calls += Triple(errorCode, approverRef, priorApproverRefs)
        return scripted
            ?: when {
                approverRef in priorApproverRefs -> ReleaseOutcome.Denied("SAME_PERSON_TWICE")
                priorApproverRefs.isEmpty() -> ReleaseOutcome.AwaitsSecond
                else -> ReleaseOutcome.Released
            }
    }
}

class FakeChecks(
    var latestCheck: LatestCheck?,
    var known: Set<String> = setOfNotNull(latestCheck?.checkId),
) : LatestChecks {
    override fun latest(
        entityRef: String,
        periodRef: String,
    ): LatestCheck? = latestCheck

    override fun knownCheckIds(
        entityRef: String,
        periodRef: String,
    ): Set<String> = known
}

class FakeModes(
    private val mode: GateMode,
) : GateModes {
    override fun modeOf(entityRef: String): GateMode = mode
}

/** A store in memory; [rendezvous] holds `findPending` callers until that many have arrived (the two-thread case). */
class FakeStore(
    private val rendezvous: CyclicBarrier? = null,
) : OverrideStore {
    private val rows = CopyOnWriteArrayList<StoredOverride>()
    var insertCalls = 0
        private set
    val rowCount get() = rows.size

    override fun insert(override: NewOverride): StoredOverride =
        synchronized(this) {
            insertCalls++
            StoredOverride(
                "o${rows.size + 1}",
                override.entityRef,
                override.periodRef,
                override.checkId,
                override.reason,
                override.status,
                override.requestedBy,
                override.releasedBy,
            ).also { rows += it }
        }

    override fun findPending(
        entityRef: String,
        periodRef: String,
        overrideId: String,
    ): StoredOverride? {
        val found =
            rows.firstOrNull {
                it.id == overrideId && it.entityRef == entityRef && it.periodRef == periodRef &&
                    it.status == OverrideStatus.PENDING
            }
        if (found != null) rendezvous?.await(5, TimeUnit.SECONDS)
        return found
    }

    @Synchronized
    override fun release(
        overrideId: String,
        releasedBy: String,
    ): Boolean {
        val i = rows.indexOfFirst { it.id == overrideId && it.status == OverrideStatus.PENDING }
        if (i < 0) return false
        rows[i] = rows[i].copy(status = OverrideStatus.RELEASED, releasedBy = releasedBy)
        return true
    }

    override fun entries(
        entityRef: String,
        periodRef: String,
    ): List<StoredOverride> = rows.filter { it.entityRef == entityRef && it.periodRef == periodRef }

    fun row(id: String): StoredOverride = rows.first { it.id == id }
}

class FakeAudit : AuditTrail {
    val requested = CopyOnWriteArrayList<Pair<String, String>>()
    val approved = CopyOnWriteArrayList<Pair<String, String>>()
    val released = CopyOnWriteArrayList<Pair<String, String>>()
    val size get() = requested.size + approved.size + released.size

    override fun requested(
        userId: String,
        override: StoredOverride,
    ) {
        requested += userId to override.id
    }

    override fun approved(
        userId: String,
        override: StoredOverride,
    ) {
        approved += userId to override.id
    }

    override fun released(
        userId: String,
        override: StoredOverride,
    ) {
        released += userId to override.id
    }
}
