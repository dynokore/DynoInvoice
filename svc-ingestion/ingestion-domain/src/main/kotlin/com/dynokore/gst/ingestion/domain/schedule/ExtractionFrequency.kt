package com.dynokore.gst.ingestion.domain.schedule

enum class ExtractionOption {
    DAILY,
    SEVERAL_DAILY,
    WEEKLY,
    ;

    companion object {
        fun fromKey(key: String): ExtractionOption =
            entries.firstOrNull { it.name == key }
                ?: throw IllegalArgumentException("Unknown extraction option: '$key'")
    }
}

data class ExtractionSetting(
    val option: ExtractionOption,
    val runsPerDay: Int?,
) {
    init {
        if (option == ExtractionOption.SEVERAL_DAILY) {
            require(runsPerDay != null && runsPerDay >= 1) { "SEVERAL_DAILY needs a run count of at least 1" }
        } else {
            require(runsPerDay == null) { "A run count is only allowed for SEVERAL_DAILY" }
        }
    }
}

enum class ExtractionRefusal {
    POINT_PLANNED,
    RUNS_REQUIRED,
    RUNS_NOT_ALLOWED,
    RUNS_OUT_OF_RANGE,
    WEEKLY_DOES_NOT_FIT,
}

sealed interface ExtractionChoice {
    data class Accepted(
        val setting: ExtractionSetting,
    ) : ExtractionChoice

    data class Refused(
        val reason: ExtractionRefusal,
    ) : ExtractionChoice
}

object ExtractionFrequency {
    val DEFAULT = ExtractionSetting(ExtractionOption.DAILY, null)

    @Suppress("LongParameterList") // six-argument signature fixed by INV-T-16261; every fact is an argument
    fun choose(
        option: ExtractionOption,
        runsPerDay: Int?,
        weeklyFits: Boolean,
        pointAvailable: Boolean,
        minRuns: Int,
        maxRuns: Int,
    ): ExtractionChoice {
        require(minRuns >= 1 && minRuns <= maxRuns) { "Run bounds must satisfy 1 <= minRuns <= maxRuns" }
        return when {
            option == ExtractionOption.DAILY -> chooseDaily(runsPerDay)
            !pointAvailable -> ExtractionChoice.Refused(ExtractionRefusal.POINT_PLANNED)
            option == ExtractionOption.SEVERAL_DAILY -> chooseSeveral(runsPerDay, minRuns, maxRuns)
            else -> chooseWeekly(runsPerDay, weeklyFits)
        }
    }

    private fun chooseDaily(runsPerDay: Int?): ExtractionChoice =
        if (runsPerDay == null) {
            ExtractionChoice.Accepted(DEFAULT)
        } else {
            ExtractionChoice.Refused(ExtractionRefusal.RUNS_NOT_ALLOWED)
        }

    private fun chooseSeveral(
        runsPerDay: Int?,
        minRuns: Int,
        maxRuns: Int,
    ): ExtractionChoice =
        when {
            runsPerDay == null -> {
                ExtractionChoice.Refused(ExtractionRefusal.RUNS_REQUIRED)
            }

            runsPerDay < minRuns || runsPerDay > maxRuns -> {
                ExtractionChoice.Refused(ExtractionRefusal.RUNS_OUT_OF_RANGE)
            }

            else -> {
                ExtractionChoice.Accepted(ExtractionSetting(ExtractionOption.SEVERAL_DAILY, runsPerDay))
            }
        }

    private fun chooseWeekly(
        runsPerDay: Int?,
        weeklyFits: Boolean,
    ): ExtractionChoice =
        when {
            runsPerDay != null -> ExtractionChoice.Refused(ExtractionRefusal.RUNS_NOT_ALLOWED)
            !weeklyFits -> ExtractionChoice.Refused(ExtractionRefusal.WEEKLY_DOES_NOT_FIT)
            else -> ExtractionChoice.Accepted(ExtractionSetting(ExtractionOption.WEEKLY, null))
        }
}
