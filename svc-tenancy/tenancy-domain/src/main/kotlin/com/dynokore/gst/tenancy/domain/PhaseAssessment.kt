package com.dynokore.gst.tenancy.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** One GST return of a prescribed accounting period: its dates and Box 4 (Box 1 + Box 2 + Box 3). */
data class GstReturn(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val box4: BigDecimal,
) {
    init {
        require(!periodEnd.isBefore(periodStart)) { "a return period ends on or after it starts" }
    }

    val days: Long get() = ChronoUnit.DAYS.between(periodStart, periodEnd) + 1
}

private const val CY2024 = 2024
private const val CY2025 = 2025
private const val CY2026 = 2026
private const val CY2028 = 2028
private const val CY2029 = 2029
private const val CY2030 = 2030
private const val CY2031 = 2031

/** The window of period-end months of INV-NOTE-2 section 2.2.1, as the first and last month summed. */
enum class PhaseWindow(
    val first: YearMonth,
    val last: YearMonth,
    private val endMonths: Set<Month>,
) {
    JAN_TO_DEC(
        YearMonth.of(CY2025, Month.JANUARY),
        YearMonth.of(CY2025, Month.DECEMBER),
        setOf(Month.MARCH, Month.JUNE, Month.SEPTEMBER, Month.DECEMBER),
    ),
    NOV_TO_OCT(
        YearMonth.of(CY2024, Month.NOVEMBER),
        YearMonth.of(CY2025, Month.OCTOBER),
        setOf(Month.JANUARY, Month.APRIL, Month.JULY, Month.OCTOBER),
    ),
    DEC_TO_NOV(
        YearMonth.of(CY2024, Month.DECEMBER),
        YearMonth.of(CY2025, Month.NOVEMBER),
        setOf(Month.FEBRUARY, Month.MAY, Month.AUGUST, Month.NOVEMBER),
    ),
    ;

    fun contains(month: YearMonth) = !month.isBefore(first) && !month.isAfter(last)

    companion object {
        fun ofPeriodEndMonth(month: Month): PhaseWindow = entries.single { month in it.endMonths }
    }
}

/**
 * One dated row of the phased-applicability table of INV-NOTE-2 section 2.2.1, in table order (contract enum
 * PhaseAssessmentResponse.phase, INV-Q-8727 A).
 */
enum class Phase(
    val mandatoryDate: LocalDate,
) {
    PHASE_1(LocalDate.of(CY2025, Month.NOVEMBER, 1)),
    PHASE_2(LocalDate.of(CY2026, Month.APRIL, 1)),
    PHASE_3(LocalDate.of(CY2028, Month.APRIL, 1)),
    PHASE_4(LocalDate.of(CY2029, Month.APRIL, 1)),
    PHASE_5(LocalDate.of(CY2030, Month.APRIL, 1)),
    PHASE_6(LocalDate.of(CY2031, Month.APRIL, 1)),
    ;

    companion object {
        fun ofMandatoryDate(date: LocalDate): Phase = entries.single { it.mandatoryDate == date }
    }
}

/**
 * The inputs of an assessment and the figures used, recorded with the result (INV-REQ-334). A request that carries one
 * Box 4 figure and no returns has no [window], no returns and no [daysCovered].
 */
data class PhaseInputs(
    val window: PhaseWindow?,
    val returnsUsed: List<GstReturn>,
    val daysCovered: Long?,
    val extrapolationChosen: Boolean,
    val summedBox4: BigDecimal,
)

/** The outcome of the assessment: the total annual supplies, the mandatory date and every input used. */
data class PhaseAssessment(
    val totalAnnualSupplies: BigDecimal,
    val mandatoryDate: LocalDate,
    val inputs: PhaseInputs,
) {
    val phase: Phase get() = Phase.ofMandatoryDate(mandatoryDate)
}

sealed interface PhaseOutcome {
    data class Assessed(
        val assessment: PhaseAssessment,
    ) : PhaseOutcome

    /** No return ends in calendar year 2025: IRAS notifies the date separately, so it stays outstanding. */
    data object Outstanding : PhaseOutcome

    /** The returns' period-end months fall in more than one row of the window table: no single window applies. */
    data class MixedPeriodEndMonths(
        val months: List<Month>,
    ) : PhaseOutcome

    /** Extrapolation was chosen but the returns cover no day. */
    data object NoDaysCovered : PhaseOutcome
}

/**
 * The onboarding phase assessment for an existing registrant (INV-REQ-334, INV-NOTE-2 sections 2.2 and 2.2.1):
 * Box 4 summed over the window the period-end months select, extrapolated by 365 / days covered only when the business
 * chose to, and the mandatory date taken from the threshold table. A total exactly at a threshold falls into the
 * earlier wave (the table's bounds are "<=").
 */
object PhaseAssessor {
    private val THRESHOLDS: List<Pair<BigDecimal, LocalDate>> =
        listOf(
            BigDecimal("200000") to Phase.PHASE_3.mandatoryDate,
            BigDecimal("1000000") to Phase.PHASE_4.mandatoryDate,
            BigDecimal("4000000") to Phase.PHASE_5.mandatoryDate,
        )
    private val ABOVE_ALL: LocalDate = Phase.PHASE_6.mandatoryDate
    private const val YEAR_DAYS = 365L

    /** The assessment of one Box 4 figure with no returns: no window, no days covered and no extrapolation. */
    fun assessFigure(box4: BigDecimal): PhaseAssessment =
        PhaseAssessment(box4, mandatoryDateOf(box4), PhaseInputs(null, emptyList(), null, false, box4))

    private fun mandatoryDateOf(total: BigDecimal): LocalDate =
        THRESHOLDS.firstOrNull { total <= it.first }?.second ?: ABOVE_ALL

    fun assess(
        returns: List<GstReturn>,
        extrapolate: Boolean,
    ): PhaseOutcome {
        val windows =
            returns
                .filter {
                    it.periodEnd.year == CY2025
                }.groupBy { PhaseWindow.ofPeriodEndMonth(it.periodEnd.month) }
        return when (windows.size) {
            0 -> {
                PhaseOutcome.Outstanding
            }

            1 -> {
                assessWindow(windows.keys.single(), windows.values.single(), extrapolate)
            }

            else -> {
                PhaseOutcome.MixedPeriodEndMonths(
                    windows.values
                        .flatten()
                        .map { it.periodEnd.month }
                        .distinct()
                        .sorted(),
                )
            }
        }
    }

    private fun assessWindow(
        window: PhaseWindow,
        ending2025: List<GstReturn>,
        extrapolate: Boolean,
    ): PhaseOutcome {
        val used = ending2025.filter { window.contains(YearMonth.from(it.periodEnd)) }
        val sum = used.fold(BigDecimal.ZERO) { acc, r -> acc + r.box4 }
        val days = used.sumOf { it.days }
        val total =
            if (extrapolate &&
                days > 0
            ) {
                sum.multiply(BigDecimal(YEAR_DAYS)).divide(BigDecimal(days), 2, RoundingMode.HALF_UP)
            } else {
                sum
            }
        val date = mandatoryDateOf(total)
        return if (extrapolate && days <= 0) {
            PhaseOutcome.NoDaysCovered
        } else {
            PhaseOutcome.Assessed(PhaseAssessment(total, date, PhaseInputs(window, used, days, extrapolate, sum)))
        }
    }
}
