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
        /**
         * The seven submission-path services that run pack-activation reprocessing or backfill and so hold the
         * reprocessing permit by default (INV-REQ-1103, owner ruling INV-Q-4023); every other service holds none.
         */
        val REPROCESSING_SERVICES: Set<String> =
            setOf("ingestion-svc", "canonical-svc", "rules-svc", "masterdata-svc", "payload-svc", "submission-svc", "transport-svc")

        /** The HA event-log pool: background only (INV-REQ-1230 b2, owner rulings INV-Q-3955, INV-Q-4023). */
        val EVENT_LOG: LaneBudgets = LaneBudgets(0, 3, 0)

        /**
         * The application pool's default budgets for [profile] and [service] (request / background / reprocessing):
         * minimal 4 / 2 / 1 or 4 / 2 / 0, HA and SaaS (per pod) 7 / 3 / 1 or 7 / 3 / 0, the reprocessing permit only
         * in [REPROCESSING_SERVICES] (INV-T-3977 capacity model).
         */
        fun defaults(
            profile: InstallationProfile,
            service: String,
        ): LaneBudgets {
            val reprocessing = if (service in REPROCESSING_SERVICES) 1 else 0
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
 * @property lanes a per-lane override of the profile default.
 */
data class PoolLaneProperties(
    val profile: InstallationProfile? = null,
    val lanes: LaneOverrides = LaneOverrides(),
) {
    /** The budgets: each lane's override, else the [profile] default for [service]; start-up fails when neither is set. */
    fun budgets(service: String?): LaneBudgets {
        val defaults =
            profile?.let { p ->
                if (lanes.request != null && lanes.background != null && lanes.reprocessing != null) {
                    null
                } else {
                    LaneBudgets.defaults(p, checkNotNull(service?.takeIf { it.isNotBlank() }) { "$PREFIX.profile needs gst.service to choose the service's defaults" })
                }
            }
        fun lane(
            name: String,
            override: Int?,
            default: (LaneBudgets) -> Int,
        ): Int = override ?: defaults?.let(default)
            ?: error("the application pool's lanes are set per installation profile (INV-REQ-1230 b1): set $PREFIX.profile or $PREFIX.lanes.$name")
        return LaneBudgets(
            lane("request", lanes.request) { it.request },
            lane("background", lanes.background) { it.background },
            lane("reprocessing", lanes.reprocessing) { it.reprocessing },
        )
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
