package com.dynokore.gst.submission.application.period

import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/** One period record in force of an entity (`PeriodRecordsChanged`, both ends inclusive). */
data class PeriodRecord(
    val periodRef: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val filingDueDate: LocalDate? = null,
)

/** The data of a `PeriodRecordsChanged` event: the complete set of period records in force at [version]. */
data class PeriodRecordsChangedData(
    val entityRef: String,
    val version: Int,
    val periods: List<PeriodRecord>,
)

/** Reads the event JSON. Throws `ContractViolation` for a missing member or a value that is not a date. */
fun interface PeriodRecordsChangedDataReader {
    fun read(dataJson: String): PeriodRecordsChangedData
}

/** Stored period records of an entity. */
interface PeriodRecordStore {
    /** The highest stored version of the entity, or null when it has no rows. */
    fun highestVersion(
        tenant: TenantId,
        entityRef: String,
    ): Int?

    /** Replaces the entity's period records; the transaction body of the consumer, so it writes nothing on failure. */
    fun replace(
        tenant: TenantId,
        entityRef: String,
        version: Int,
        periods: List<PeriodRecord>,
    )
}

/** Reads the stored periods that contain a date. */
fun interface PeriodContainingReader {
    /** The period references of the stored periods with start on or before [date] and end on or after it. */
    fun containing(
        tenant: TenantId,
        entityRef: String,
        date: LocalDate,
    ): List<String>
}
