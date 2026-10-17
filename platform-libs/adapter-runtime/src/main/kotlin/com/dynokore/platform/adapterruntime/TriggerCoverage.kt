package com.dynokore.platform.adapterruntime

/** Result of [TriggerCoverage.evaluate]. */
sealed interface CoverageVerdict {
    /** Every class has an entry; [systemClasses] holds only the classes the System covers (mapped or waived). */
    data class Complete(
        val systemClasses: Set<TriggerClass>,
    ) : CoverageVerdict

    /** [missing] names every class with no entry, in declaration order (INV-AC-2029). */
    data class Incomplete(
        val missing: List<TriggerClass>,
    ) : CoverageVerdict
}

/** Pure trigger-class coverage decision (INV-REQ-788, INV-REQ-953, INV-REQ-1322). Holds no default and reads no clock. */
object TriggerCoverage {
    const val ENTITY_ADMIN: String = "entity.admin"

    /** Waivers are re-presented at readiness checks 1 to this number inclusive (INV-REQ-953). */
    private const val REPRESENT_CHECKS = 4

    fun evaluate(entries: Map<TriggerClass, TriggerCoverageEntry>): CoverageVerdict {
        val missing = TriggerClass.entries.filter { it !in entries }
        if (missing.isNotEmpty()) return CoverageVerdict.Incomplete(missing)
        val system =
            TriggerClass.entries
                .filter { entries.getValue(it) !is TriggerCoverageEntry.CoveredElsewhere }
                .toSet()
        return CoverageVerdict.Complete(system)
    }

    /** Waived classes to re-present at the [readinessCheckNumber]-th check since go-live (1-based). */
    fun waiversToRepresent(
        entries: Map<TriggerClass, TriggerCoverageEntry>,
        readinessCheckNumber: Int,
    ): Set<TriggerClass> {
        require(readinessCheckNumber >= 1) { "readinessCheckNumber must be at least 1: $readinessCheckNumber" }
        if (readinessCheckNumber > REPRESENT_CHECKS) return emptySet()
        return TriggerClass.entries.filter { entries[it] is TriggerCoverageEntry.Waived }.toSet()
    }
}
