package com.dynokore.gst.submission.application.pack

import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

enum class MarginUnit { DAYS, HOURS }

/** A safety margin read from a calendar pack; only [of] builds one, so every instance is within the pack contract. */
class PackMargin private constructor(
    val value: Int,
    val unit: MarginUnit,
) {
    override fun equals(other: Any?): Boolean = other is PackMargin && other.value == value && other.unit == unit

    override fun hashCode(): Int = 31 * value + unit.hashCode()

    override fun toString(): String = "PackMargin($value $unit)"

    companion object {
        private const val MIN_VALUE = 0L
        private const val MAX_VALUE = 8784L
        private const val MAX_DAYS = 366L

        /** Null unless the value is 0..8784, the unit is exactly DAYS or HOURS, and DAYS is at most 366. */
        fun of(
            value: Long?,
            unit: String?,
        ): PackMargin? {
            val parsed = MarginUnit.entries.firstOrNull { it.name == unit }
            val limit = if (parsed == MarginUnit.DAYS) MAX_DAYS else MAX_VALUE
            return if (parsed != null && value != null && value in MIN_VALUE..limit) {
                PackMargin(value.toInt(), parsed)
            } else {
                null
            }
        }
    }
}

/** The margin as the event carries it, before validation. */
data class RawMargin(
    val value: Long?,
    val unit: String?,
)

data class PackStagedData(
    val packIdentifier: String,
    val packType: String,
    val version: String,
    val margin: RawMargin?,
)

data class PackActivatedData(
    val packIdentifier: String,
    val version: String,
    val effectiveFrom: LocalDate,
)

/** Throws `ContractViolation` for a missing packIdentifier, packType, version or a non-date effectiveFrom. */
fun interface PackStagedDataReader {
    fun read(dataJson: String): PackStagedData
}

/** Throws `ContractViolation` for a missing packIdentifier, version or effectiveFrom, or a value that is not a date. */
fun interface PackActivatedDataReader {
    fun read(dataJson: String): PackActivatedData
}

/** Writes inside the consumer transaction; each call returns true when it inserted, false when the row exists. */
interface PackMarginStore {
    fun stageCalendar(
        tenant: TenantId,
        packIdentifier: String,
        version: String,
        margin: PackMargin?,
    ): Boolean

    fun recordActivation(
        tenant: TenantId,
        packIdentifier: String,
        version: String,
        effectiveFrom: LocalDate,
    ): Boolean
}

data class CalendarActivation(
    val packIdentifier: String,
    val version: String,
    val effectiveFrom: LocalDate,
    val margin: PackMargin?,
)

/** Activations whose identifier and version are a staged calendar version, effective on or before the date. */
fun interface CalendarActivationReader {
    fun calendarActivations(
        tenant: TenantId,
        onOrBefore: LocalDate,
    ): List<CalendarActivation>
}
