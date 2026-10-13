package com.dynokore.platform.serviceruntime.pool

/**
 * The permit budget of each lane of one pool; the pool maximum is their sum (INV-REQ-1230 b1, INV-ADR-3968 item 1;
 * owner ruling INV-Q-4023).
 */
data class LaneBudgets(
    val request: Int,
    val background: Int,
    val reprocessing: Int,
) {
    init {
        require(request >= 0 && background >= 0 && reprocessing >= 0) { "a lane budget is never negative: $this" }
        require(maximum >= 1) { "a pool needs at least one permit: $this" }
    }

    /** The pool maximum: the sum of the budgets. */
    val maximum: Int get() = request + background + reprocessing

    fun of(lane: PoolLane): Int = when (lane) {
        PoolLane.REQUEST -> request
        PoolLane.BACKGROUND -> background
        PoolLane.REPROCESSING -> reprocessing
    }

    override fun toString(): String = "$request / $background / $reprocessing"

    companion object {
        /** The HA event-log pool: background only (INV-REQ-1230 b2, owner rulings INV-Q-3955, INV-Q-4023). */
        val EVENT_LOG: LaneBudgets = LaneBudgets(0, 3, 0)

        /**
         * The application pool's default budgets for [profile] (request / background / reprocessing): minimal 4 / 2 / 1
         * or 4 / 2 / 0, HA and SaaS (per pod) 7 / 3 / 1 or 7 / 3 / 0, the reprocessing permit only when the service
         * declares that it runs reprocessing or backfill (INV-T-3977 capacity model; owner ruling INV-Q-4023,
         * coordinator ruling INV-Q-4485).
         */
        fun defaults(
            profile: InstallationProfile,
            runsReprocessing: Boolean,
        ): LaneBudgets {
            val reprocessing = if (runsReprocessing) 1 else 0
            return when (profile) {
                InstallationProfile.MINIMAL -> LaneBudgets(4, 2, reprocessing)
                InstallationProfile.HA, InstallationProfile.SAAS -> LaneBudgets(7, 3, reprocessing)
            }
        }
    }
}

/** The installation profile whose lane defaults apply (INV-REQ-1230 b4: the values are set per profile). */
enum class InstallationProfile { MINIMAL, HA, SAAS }

/**
 * `service-runtime.pool.*`: the application pool's lanes.
 *
 * @property profile the installation profile whose defaults ([LaneBudgets.defaults]) apply.
 * @property runsReprocessing the service's own declaration that it runs pack-activation reprocessing or backfill, set
 *   in its packaged configuration (coordinator ruling INV-Q-4485); service-runtime holds no list of services.
 * @property lanes a per-lane override of the profile default.
 */
data class PoolLaneProperties(
    val profile: InstallationProfile? = null,
    val runsReprocessing: Boolean = false,
    val lanes: LaneOverrides = LaneOverrides(),
) {
    /**
     * The budgets: each lane's override, else the [profile] default. Start-up fails when neither is set, when a service
     * that declares reprocessing has no reprocessing permit (its work would otherwise run on a live lane), and when a
     * service that does not declare it has one (INV-Q-4023, INV-Q-4485).
     */
    fun budgets(): LaneBudgets {
        val defaults = profile?.let { LaneBudgets.defaults(it, runsReprocessing) }
        fun lane(
            name: String,
            override: Int?,
            default: (LaneBudgets) -> Int,
        ): Int = override ?: defaults?.let(default)
            ?: error("the application pool's lanes are set per installation profile (INV-REQ-1230 b1): set $PREFIX.profile or $PREFIX.lanes.$name")
        val budgets = LaneBudgets(
            lane("request", lanes.request) { it.request },
            lane("background", lanes.background) { it.background },
            lane("reprocessing", lanes.reprocessing) { it.reprocessing },
        )
        if (runsReprocessing) {
            check(budgets.reprocessing > 0) {
                "$PREFIX.runs-reprocessing is true, but the reprocessing budget is 0: reprocessing and backfill take only " +
                    "reprocessing permits and never run on a live lane (INV-REQ-1072 b5, INV-Q-4023)"
            }
        } else {
            check(budgets.reprocessing == 0) {
                "the reprocessing budget is ${budgets.reprocessing}, but $PREFIX.runs-reprocessing is false: only a service " +
                    "that declares reprocessing holds reprocessing permits (INV-Q-4485)"
            }
        }
        return budgets
    }

    companion object {
        const val PREFIX = "service-runtime.pool"
    }
}

/** Per-lane overrides of the profile defaults. */
data class LaneOverrides(
    val request: Int? = null,
    val background: Int? = null,
    val reprocessing: Int? = null,
)
