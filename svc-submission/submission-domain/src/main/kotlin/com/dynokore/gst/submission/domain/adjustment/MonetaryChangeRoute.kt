package com.dynokore.gst.submission.domain.adjustment

/** The options of the entity setting for the default route of a monetary change (keys of the catalogue row). */
enum class RouteOption {
    ASK_APPROVER,
    ALWAYS_CREDIT_NOTE,
    KEYING_CORRECTION_WINDOW,
    ;

    companion object {
        /** The member named exactly [key]; any other string, including a different case, is refused. */
        fun fromKey(key: String): RouteOption =
            entries.firstOrNull { it.name == key }
                ?: throw IllegalArgumentException("Unknown route option key: '$key'")
    }
}

/** The outcomes a monetary change to a transmitted document can take. */
enum class ChangeOutcome { CREDIT_NOTE, KEYING_CORRECTION }

/** A chosen setting; the window in days exists only for [RouteOption.KEYING_CORRECTION_WINDOW]. */
data class RouteSetting(
    val option: RouteOption,
    val windowDays: Int?,
) {
    init {
        if (option == RouteOption.KEYING_CORRECTION_WINDOW) {
            require(windowDays != null && windowDays >= 1) { "windowDays must be at least 1 for $option" }
        } else {
            require(windowDays == null) { "windowDays must be null for $option" }
        }
    }
}

/** Why a choice of setting is refused. */
enum class RouteRefusal {
    OUTCOME_FORBIDDEN,
    OPTION_UNAVAILABLE,
    WINDOW_REQUIRED,
    WINDOW_NOT_ALLOWED,
    WINDOW_OUT_OF_RANGE,
}

/** The answer of [MonetaryChangeRoute.choose]. */
sealed interface RouteChoice {
    data class Accepted(
        val setting: RouteSetting,
    ) : RouteChoice

    data class Refused(
        val reason: RouteRefusal,
    ) : RouteChoice
}

/** Pure rule of the default route for a monetary change; every decision and catalogue fact is an argument. */
object MonetaryChangeRoute {
    /** Ask the approver each time. */
    val DEFAULT = RouteSetting(RouteOption.ASK_APPROVER, null)

    /** Validates a requested setting against the allowed outcomes, the keying availability and the window bounds. */
    @Suppress("LongParameterList")
    fun choose(
        option: RouteOption,
        windowDays: Int?,
        allowed: Set<ChangeOutcome>,
        keyingAvailable: Boolean,
        minDays: Int,
        maxDays: Int,
    ): RouteChoice {
        require(minDays >= 1 && minDays <= maxDays) { "bounds must satisfy 1 <= minDays <= maxDays" }
        return when (option) {
            RouteOption.ASK_APPROVER -> {
                if (windowDays != null) refused(RouteRefusal.WINDOW_NOT_ALLOWED) else RouteChoice.Accepted(DEFAULT)
            }

            RouteOption.ALWAYS_CREDIT_NOTE -> {
                chooseCreditNote(windowDays, allowed)
            }

            RouteOption.KEYING_CORRECTION_WINDOW -> {
                chooseWindow(windowDays, allowed, keyingAvailable, minDays, maxDays)
            }
        }
    }

    private fun chooseCreditNote(
        windowDays: Int?,
        allowed: Set<ChangeOutcome>,
    ): RouteChoice =
        when {
            windowDays != null -> refused(RouteRefusal.WINDOW_NOT_ALLOWED)
            ChangeOutcome.CREDIT_NOTE !in allowed -> refused(RouteRefusal.OUTCOME_FORBIDDEN)
            else -> RouteChoice.Accepted(RouteSetting(RouteOption.ALWAYS_CREDIT_NOTE, null))
        }

    @Suppress("LongParameterList")
    private fun chooseWindow(
        windowDays: Int?,
        allowed: Set<ChangeOutcome>,
        keyingAvailable: Boolean,
        minDays: Int,
        maxDays: Int,
    ): RouteChoice =
        when {
            !keyingAvailable -> refused(RouteRefusal.OPTION_UNAVAILABLE)
            ChangeOutcome.KEYING_CORRECTION !in allowed -> refused(RouteRefusal.OUTCOME_FORBIDDEN)
            windowDays == null -> refused(RouteRefusal.WINDOW_REQUIRED)
            windowDays < minDays || windowDays > maxDays -> refused(RouteRefusal.WINDOW_OUT_OF_RANGE)
            else -> RouteChoice.Accepted(RouteSetting(RouteOption.KEYING_CORRECTION_WINDOW, windowDays))
        }

    /** The outcomes offered to the approver; always a subset of [allowed]. */
    fun offer(
        setting: RouteSetting,
        daysSinceTransmission: Int,
        allowed: Set<ChangeOutcome>,
        keyingAvailable: Boolean,
    ): Set<ChangeOutcome> {
        require(daysSinceTransmission >= 0) { "daysSinceTransmission must not be negative" }
        val keyingOffered =
            keyingAvailable &&
                ChangeOutcome.KEYING_CORRECTION in allowed &&
                when (setting.option) {
                    RouteOption.ASK_APPROVER -> true
                    RouteOption.ALWAYS_CREDIT_NOTE -> false
                    RouteOption.KEYING_CORRECTION_WINDOW -> daysSinceTransmission <= (setting.windowDays ?: -1)
                }
        return buildSet {
            if (ChangeOutcome.CREDIT_NOTE in allowed) add(ChangeOutcome.CREDIT_NOTE)
            if (keyingOffered) add(ChangeOutcome.KEYING_CORRECTION)
        }
    }

    private fun refused(reason: RouteRefusal): RouteChoice = RouteChoice.Refused(reason)
}
