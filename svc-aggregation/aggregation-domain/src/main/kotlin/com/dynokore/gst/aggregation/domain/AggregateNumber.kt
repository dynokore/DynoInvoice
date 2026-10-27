package com.dynokore.gst.aggregation.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** What numbering decided: the number, or the fact that it exceeds the `documentNumber` bound (INV-REQ-436). */
sealed interface NumberingResult {
    data class Numbered(
        val number: String,
    ) : NumberingResult

    data class TooLong(
        val length: Int,
    ) : NumberingResult
}

/**
 * The deterministic document number of an aggregate (INV-REQ-436, FR-AE.50):
 * `AGG-{entity}-{type}-{businessUnit}-{periodEnd:yyyymmdd}-{seq}`, entity and business unit by their registered short
 * codes. The same inputs give the same number, so a replay reproduces it. The issue and transaction dates of an
 * aggregate are its period end ([issueDate]).
 */
object AggregateNumber {
    /** The `documentNumber` bound, string(64) (INV-NOTE-3 §20.2, ruled in INV-Q-1560). */
    const val MAX_LENGTH = 64

    private val periodEndFormat = DateTimeFormatter.BASIC_ISO_DATE

    fun of(
        entityShortCode: String,
        type: AggregateType,
        businessUnitShortCode: String,
        periodEnd: LocalDate,
        sequence: Long,
    ): NumberingResult {
        require(entityShortCode.isNotEmpty() && businessUnitShortCode.isNotEmpty()) { "a short code is not empty" }
        require(sequence >= 1) { "a sequence starts at 1" }
        val number =
            "AGG-$entityShortCode-${type.name}-$businessUnitShortCode-${periodEndFormat.format(periodEnd)}-$sequence"
        return if (number.length <= MAX_LENGTH) {
            NumberingResult.Numbered(number)
        } else {
            NumberingResult.TooLong(number.length)
        }
    }

    /** Issue date and transaction date of an aggregate: its period end. */
    fun issueDate(periodEnd: LocalDate): LocalDate = periodEnd
}
