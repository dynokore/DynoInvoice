package com.dynokore.platform.adapterruntime.cutover

import com.dynokore.platform.adapterruntime.AdapterVersion
import com.dynokore.platform.adapterruntime.CutoverRule
import com.dynokore.platform.adapterruntime.ManagedAdapter
import java.time.Clock
import java.time.Instant

/**
 * Runs every registered version of one adapter side by side and routes each new unit
 * of work to whichever one [rule] currently names (INV-REQ-1354). A version [rule]
 * stops naming is not stopped immediately: it is only [retired][retireInactive],
 * which drains it — new work never reaches it again, but the work it already
 * accepted is allowed to finish.
 *
 * [clock] is always supplied by the caller (CS-10): this class never reads the
 * system clock itself.
 */
class VersionedAdapterRouter<A : ManagedAdapter>(
    versions: Collection<A>,
    private val rule: CutoverRule,
    private val clock: Clock,
) {
    private val byVersion: Map<AdapterVersion, A> =
        versions.associateBy { it.version }.also {
            require(it.size == versions.size) { "VersionedAdapterRouter was given more than one adapter for the same version" }
        }

    init {
        require(byVersion.isNotEmpty()) { "VersionedAdapterRouter needs at least one adapter version" }
        byVersion.values.forEach(ManagedAdapter::start)
    }

    /** The adapter [rule] currently designates as active; every new unit of work goes here. */
    fun current(): A {
        val activeVersion = rule.activeVersion(clock.instant())
        return byVersion[activeVersion]
            ?: error("CutoverRule named version $activeVersion, which was never registered with this router")
    }

    /**
     * Drains and stops every registered version other than the one currently active,
     * waiting at most until [deadline] for each. Safe to call repeatedly as a cutover
     * progresses: a version already draining or stopped is left alone.
     */
    fun retireInactive(deadline: Instant) {
        val activeVersion = rule.activeVersion(clock.instant())
        byVersion.values
            .filter { it.version != activeVersion }
            .forEach { it.drainAndStop(deadline) }
    }
}
