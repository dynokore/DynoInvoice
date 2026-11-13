package com.dynokore.gst.consoleBff.application.duedates

import java.time.LocalDate

/** The period a due date belongs to, within one tenant (INV-AC-21076). */
data class PeriodKey(
    val entityRef: String,
    val periodRef: String,
)

/** The facts of a `PeriodDueDateChanged` event: only the new date and its version are kept. */
data class DueDateChange(
    val key: PeriodKey,
    val newDueDate: LocalDate,
    val version: Long,
)

/** The facts of a `PeriodRecordsChanged` event: the filing due date per period that carries one. */
data class InitialDueDates(
    val entityRef: String,
    val byPeriod: Map<String, LocalDate>,
)

/**
 * The due-date store keyed (tenant, entityRef, periodRef). Version 0 is the initial value from a period record; a
 * change event carries at least 1 and so always supersedes it.
 */
interface PeriodDueDateStore {
    /** Writes when [version] is greater than the held version (or no row exists); true when it wrote. */
    fun applyIfNewer(
        tenantId: String,
        key: PeriodKey,
        dueDate: LocalDate,
        version: Long,
    ): Boolean

    /** Writes version 0 only when no row exists; true when it wrote. */
    fun putInitialIfAbsent(
        tenantId: String,
        key: PeriodKey,
        dueDate: LocalDate,
    ): Boolean

    fun dueDates(
        tenantId: String,
        keys: Set<PeriodKey>,
    ): Map<PeriodKey, LocalDate>
}

/** Reads the PeriodDueDateChanged payload; any missing or invalid required fact is a `ContractViolation`. */
interface PeriodDueDateChangedReader {
    fun read(dataJson: String): DueDateChange
}

/** Reads the PeriodRecordsChanged payload; a present, non-date `filingDueDate` is a `ContractViolation`. */
interface PeriodRecordsChangedReader {
    fun read(dataJson: String): InitialDueDates
}
