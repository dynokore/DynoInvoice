package com.dynokore.gst.rules.domain.pack

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The silence limit of DEP-001 (VP-72, INV-REQ-2468): 7 days by default, up to 30 days for an air-gapped installation,
 * never beyond 30. The bounds are enforced where the limit is set (the shared `setPolicy`); a value this reader meets
 * outside them is never applied and the default stands (fail closed to the stricter limit).
 */
@JvmInline
value class SilenceLimit private constructor(
    val days: Int,
) {
    companion object {
        const val DEFAULT_DAYS = 7
        const val MAX_DAYS = 30
        val DEFAULT = SilenceLimit(DEFAULT_DAYS)

        /** The limit a stored VP-72 value names (`DAYS_7`, or a whole number of days 7 to 30), else the default. */
        fun ofPolicyValue(value: String?): SilenceLimit {
            val days =
                when {
                    value == null -> null
                    value == "DAYS_7" -> DEFAULT_DAYS
                    PLAIN.matches(value) -> value.toIntOrNull()
                    else -> null
                }
            return if (days != null && days in DEFAULT_DAYS..MAX_DAYS) SilenceLimit(days) else DEFAULT
        }

        private val PLAIN = Regex("[0-9]{1,3}")
    }
}

/** A newer pack that is published and not activated: the unactivated pack DEP-001 escalates against. */
data class UnactivatedPack(
    val packIdentifier: String,
    val packVersion: String,
    val effectiveFrom: LocalDate,
)

/**
 * The DEP-001 condition as one comparable tuple (INV-REQ-1080, INV-REQ-2468). The event is published only when this
 * tuple differs from the last published tuple of the condition (ruling, INV-T-4976 gap 7).
 */
data class PackCurrencyState(
    val depCode: String,
    val raised: Boolean,
    val packIdentifier: String?,
    val packVersion: String?,
    val daysToEffectiveDate: Int?,
    val daysSinceLastReached: Int?,
)

/** Pure DEP-001 evaluation: no clock of its own, the day is the caller's (ARC-13). */
object PackCurrency {
    const val DEP_CODE = "DEP-001"

    /** Whole days from [from] to [to] by calendar day; never negative. */
    fun daysBetween(
        from: LocalDate,
        to: LocalDate,
    ): Int = ChronoUnit.DAYS.between(from, to).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    /**
     * DEP-001 is raised when the feed has been silent for MORE than [limit] days (7 days is not yet silence, the eighth
     * is), or when a newer pack is published and not activated. [lastReached] is the day the feed was last reached or a
     * bundle last imported; a null value counts as no silence yet only when there is nothing to measure from, so the
     * caller passes the installation day in that case.
     */
    fun evaluate(
        lastReached: LocalDate,
        today: LocalDate,
        limit: SilenceLimit,
        unactivated: UnactivatedPack?,
    ): PackCurrencyState {
        val silent = daysBetween(lastReached, today)
        val silenceRaised = silent > limit.days
        val raised = silenceRaised || unactivated != null
        if (!raised) return PackCurrencyState(DEP_CODE, false, null, null, null, null)
        return PackCurrencyState(
            depCode = DEP_CODE,
            raised = true,
            packIdentifier = unactivated?.packIdentifier,
            packVersion = unactivated?.packVersion,
            daysToEffectiveDate = unactivated?.let { daysBetween(today, it.effectiveFrom) },
            daysSinceLastReached = silent,
        )
    }

    /** True when [next] must be published: it differs from [last] (null when nothing was published yet). */
    fun mustPublish(
        last: PackCurrencyState?,
        next: PackCurrencyState,
    ): Boolean = if (last == null) next.raised else last != next
}
