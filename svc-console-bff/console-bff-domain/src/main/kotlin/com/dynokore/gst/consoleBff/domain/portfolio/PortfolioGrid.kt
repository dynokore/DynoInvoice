package com.dynokore.gst.consoleBff.domain.portfolio

import java.time.LocalDate

enum class PeriodState { OPEN, DUE, FILED, LATE }

sealed interface Readiness {
    object Ready : Readiness

    data class Blocked(
        val count: Int,
    ) : Readiness

    object Unavailable : Readiness
}

data class PeriodFact(
    val periodRef: String,
    val state: PeriodState,
    val filingDueDate: LocalDate,
    val filingDate: LocalDate?,
    val extensionGranted: Boolean,
    val readiness: Readiness?,
)

enum class Verdict { READY, BLOCKED, UNABLE_TO_DETERMINE }

data class Cell(
    val state: PeriodState,
    val filingDate: LocalDate?,
    val extensionGranted: Boolean,
    val verdict: Verdict?,
    val blockedCount: Int?,
)

data class PortfolioRow(
    val entityRef: String,
    val cells: Map<String, Cell>,
)

data class Portfolio(
    val periodRefs: List<String>,
    val rows: List<PortfolioRow>,
)

/**
 * INV-REQ-904: one row per entity, one column per period not yet filed by every entity, no score.
 * The state is read from the period record (INV-REQ-85), never recomputed (INV-Q-15811, option A).
 */
fun buildPortfolio(facts: Map<String, List<PeriodFact>>): Portfolio {
    val earliestByPeriod = HashMap<String, LocalDate>()
    for (list in facts.values) {
        for (f in list) {
            if (f.filingDate == null) {
                earliestByPeriod.merge(f.periodRef, f.filingDueDate) { a, b -> if (b < a) b else a }
            }
        }
    }
    val periodRefs =
        earliestByPeriod.entries
            .sortedWith(compareBy<Map.Entry<String, LocalDate>> { it.value }.thenBy { it.key })
            .map { it.key }

    val rows =
        facts.entries.map { (entityRef, list) ->
            val cells = LinkedHashMap<String, Cell>()
            for (periodRef in periodRefs) {
                val fact = list.firstOrNull { it.periodRef == periodRef } ?: continue
                cells[periodRef] = toCell(fact)
            }
            val unfiled = list.filter { it.filingDate == null }
            val nearest = unfiled.minOfOrNull { it.filingDueDate }
            Triple(entityRef, nearest, PortfolioRow(entityRef, cells))
        }
    val sorted =
        rows.sortedWith(
            compareBy<Triple<String, LocalDate?, PortfolioRow>> { it.second == null }
                .thenBy { it.second }
                .thenBy { it.first },
        )
    return Portfolio(periodRefs, sorted.map { it.third })
}

private fun toCell(f: PeriodFact): Cell {
    fun cell(
        verdict: Verdict?,
        count: Int? = null,
    ) = Cell(f.state, f.filingDate, f.extensionGranted, verdict, count)
    if (f.state != PeriodState.DUE) return cell(null)
    return when (val r = f.readiness) {
        Readiness.Ready -> cell(Verdict.READY)
        is Readiness.Blocked -> cell(Verdict.BLOCKED, r.count)
        Readiness.Unavailable, null -> cell(Verdict.UNABLE_TO_DETERMINE)
    }
}
