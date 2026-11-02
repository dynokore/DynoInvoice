package com.dynokore.gst.submission.domain.schedule

/** The VP-20 options of the entity (INV-REQ-1281); the keys are those of the catalogue row. */
enum class DespatchOption {
    CONTINUOUS,
    DAILY_WINDOW,
    WEEKLY_WINDOW,
    ;

    companion object {
        /** The member named exactly [key]; any other string, including a different case, is refused. */
        fun fromKey(key: String): DespatchOption =
            entries.firstOrNull { it.name == key }
                ?: throw IllegalArgumentException("Unknown despatch option key: '$key'")
    }
}

/** Why a despatch option is refused. */
enum class DespatchRefusal {
    WEEKLY_DOES_NOT_FIT,
}

/** The answer of [DespatchWindowSetting.choose]. */
sealed interface DespatchChoice {
    data class Accepted(
        val option: DespatchOption,
    ) : DespatchChoice

    data class Refused(
        val reason: DespatchRefusal,
    ) : DespatchChoice
}

/** The pure rule of the VP-20 setting (INV-REQ-1281 behavior 2, INV-AC-16257). */
object DespatchWindowSetting {
    /** Continuous despatch is the default of an entity. */
    val DEFAULT = DespatchOption.CONTINUOUS

    /**
     * Decides [option]. [weeklyFits] is the result of the calendar check computed by the caller,
     * which passes false when the fit cannot be computed.
     */
    fun choose(
        option: DespatchOption,
        weeklyFits: Boolean,
    ): DespatchChoice =
        when (option) {
            DespatchOption.CONTINUOUS, DespatchOption.DAILY_WINDOW -> {
                DespatchChoice.Accepted(option)
            }

            DespatchOption.WEEKLY_WINDOW -> {
                if (weeklyFits) {
                    DespatchChoice.Accepted(option)
                } else {
                    DespatchChoice.Refused(DespatchRefusal.WEEKLY_DOES_NOT_FIT)
                }
            }
        }
}
