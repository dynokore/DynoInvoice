package com.dynokore.gst.transport.domain

import java.math.BigDecimal

/** Why a tenant value for the throughput ceiling was refused (INV-REQ-1317, tighten-only). */
enum class CeilingRefusal {
    /** Zero or negative: not a lower value (min_exclusive 0 of the catalogue). */
    NOT_POSITIVE,

    /** Above the rate agreed with the AP: refused as such, not as "not yet offered" (INV-AC-2305). */
    ABOVE_AGREED_RATE,
}

sealed interface CeilingChange {
    data class Applied(
        val ceiling: ThroughputCeiling,
    ) : CeilingChange

    data class Refused(
        val reason: CeilingRefusal,
        val ceiling: ThroughputCeiling,
    ) : CeilingChange
}

/**
 * The throughput ceiling of an AP connection, in documents per minute: the rate agreed with the AP, captured at
 * connection set-up, or a lower tenant value (INV-REQ-1317). A refused change leaves the agreed rate in force.
 */
class ThroughputCeiling private constructor(
    val agreedRate: BigDecimal,
    val tenantValue: BigDecimal?,
) {
    /** Without a tenant value the ceiling equals the agreed rate. */
    val effective: BigDecimal get() = tenantValue ?: agreedRate

    fun withTenantValue(value: BigDecimal): CeilingChange =
        when {
            value.signum() <= 0 -> CeilingChange.Refused(CeilingRefusal.NOT_POSITIVE, this)
            value > agreedRate -> CeilingChange.Refused(CeilingRefusal.ABOVE_AGREED_RATE, this)
            else -> CeilingChange.Applied(ThroughputCeiling(agreedRate, value))
        }

    companion object {
        fun agreed(rate: BigDecimal): ThroughputCeiling {
            require(rate.signum() > 0) { "the agreed rate is above zero" }
            return ThroughputCeiling(rate, null)
        }

        /** Rebuilds a stored ceiling; a stored tenant value above the agreed rate is corrupt data, never trusted. */
        fun restore(
            agreedRate: BigDecimal,
            tenantValue: BigDecimal?,
        ): ThroughputCeiling {
            val base = agreed(agreedRate)
            if (tenantValue == null) return base
            return when (val change = base.withTenantValue(tenantValue)) {
                is CeilingChange.Applied -> change.ceiling
                is CeilingChange.Refused -> error("stored tenant ceiling $tenantValue refused: ${change.reason}")
            }
        }
    }
}
