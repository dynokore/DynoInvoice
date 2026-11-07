package com.dynokore.gst.ingestion.domain.backfill

import java.time.LocalDate

enum class BackfillSituation {
    EARLY_ADOPTION,
    SILENT_FAILURE_CORRECTION,
    MID_PERIOD_MIGRATION,
    ;

    companion object {
        fun fromKey(key: String): BackfillSituation =
            entries.firstOrNull { it.name == key }
                ?: throw IllegalArgumentException("Unknown backfill situation: '$key'")
    }
}

data class DatePeriod(
    val start: LocalDate,
    val end: LocalDate,
) {
    init {
        require(!start.isAfter(end)) { "Period start $start is after its end $end" }
    }

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
}

enum class BackfillRefusal {
    BEFORE_ACTIVATION,
    AFFECTED_PERIOD_REQUIRED,
    OUTSIDE_AFFECTED_PERIOD,
    OUTSIDE_CURRENT_PERIOD,
}

sealed interface BackfillDecision {
    object Accepted : BackfillDecision

    data class Refused(
        val reason: BackfillRefusal,
    ) : BackfillDecision
}

object BackfillScope {
    fun decide(
        situation: BackfillSituation,
        transactionDate: LocalDate,
        activationDate: LocalDate,
        affectedPeriod: DatePeriod?,
        currentPeriod: DatePeriod,
    ): BackfillDecision =
        when (situation) {
            BackfillSituation.EARLY_ADOPTION -> {
                if (transactionDate.isBefore(activationDate)) {
                    BackfillDecision.Refused(BackfillRefusal.BEFORE_ACTIVATION)
                } else {
                    BackfillDecision.Accepted
                }
            }

            BackfillSituation.SILENT_FAILURE_CORRECTION -> {
                when {
                    affectedPeriod == null -> {
                        BackfillDecision.Refused(BackfillRefusal.AFFECTED_PERIOD_REQUIRED)
                    }

                    transactionDate !in affectedPeriod -> {
                        BackfillDecision.Refused(BackfillRefusal.OUTSIDE_AFFECTED_PERIOD)
                    }

                    else -> {
                        BackfillDecision.Accepted
                    }
                }
            }

            BackfillSituation.MID_PERIOD_MIGRATION -> {
                if (transactionDate in currentPeriod) {
                    BackfillDecision.Accepted
                } else {
                    BackfillDecision.Refused(BackfillRefusal.OUTSIDE_CURRENT_PERIOD)
                }
            }
        }
}
