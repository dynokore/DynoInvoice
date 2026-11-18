package com.dynokore.gst.reconciliation.domain.pack

import java.time.LocalDate

/** One pack version and the date it becomes effective; its range runs to the day before the next greater date. */
data class PackVersionWindow(
    val packVersion: String,
    val effectiveFrom: LocalDate,
) {
    init {
        require(packVersion.isNotBlank()) { "packVersion must not be blank" }
    }
}

sealed interface PackVersionChoice {
    data class Chosen(
        val version: PackVersionWindow,
    ) : PackVersionChoice

    data class NoVersionCovers(
        val lastDay: LocalDate,
    ) : PackVersionChoice
}

/** Pure choice of the pack version whose effective range contains the last day of a return period. */
object PackVersionResolver {
    fun resolve(
        lastDayOfPeriod: LocalDate,
        versions: List<PackVersionWindow>,
    ): PackVersionChoice {
        require(versions.map { it.effectiveFrom }.toSet().size == versions.size) {
            "two pack versions share the same effectiveFrom"
        }
        require(versions.map { it.packVersion }.toSet().size == versions.size) {
            "the same pack version appears twice"
        }
        val covering = versions.filter { !it.effectiveFrom.isAfter(lastDayOfPeriod) }.maxByOrNull { it.effectiveFrom }
        return if (covering == null) {
            PackVersionChoice.NoVersionCovers(lastDayOfPeriod)
        } else {
            PackVersionChoice.Chosen(covering)
        }
    }
}
