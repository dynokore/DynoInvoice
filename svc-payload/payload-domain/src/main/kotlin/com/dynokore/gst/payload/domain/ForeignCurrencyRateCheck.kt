package com.dynokore.gst.payload.domain

import java.math.BigDecimal
import java.time.LocalDate

/** A rate held for one source, currency and date (INV-REQ-506). */
data class RateOnFile(
    val source: String,
    val currency: String,
    val date: LocalDate,
    val rate: BigDecimal,
)

/** The five SGD tax-currency properties of the request `totals` object (INV-REQ-466, INV-REQ-467). */
data class StatedSgd(
    val exchangeRate: BigDecimal?,
    val exchangeRateSource: String?,
    val sgdTotalExcludingTax: String?,
    val sgdTotalTax: String?,
    val sgdTotalIncludingTax: String?,
)

/** The scope-pack tolerance datum (INV-REQ-1298); the unit is a free string, only "percent" is valid. */
data class RateTolerance(
    val value: BigDecimal,
    val unit: String,
)

/** Why PAY-006 rejects a stated rate (INV-Q-6109 vocabulary). */
enum class RateReason { SOURCE_NOT_ELECTED, STATED_RATE_NOT_POSITIVE, OUTSIDE_TOLERANCE }

/** The verdict of [ForeignCurrencyRateCheck.check]; each case that is a finding exposes its catalogue code. */
sealed interface RateVerdict {
    data object NotApplicable : RateVerdict

    data class MissingSgdFields(
        val fields: List<String>,
    ) : RateVerdict {
        val code: String get() = ForeignCurrencyRateCheck.PAY_005
    }

    data object NoElection : RateVerdict {
        val code: String get() = ForeignCurrencyRateCheck.PAY_016
    }

    data object NoRateOnFile : RateVerdict {
        val code: String get() = ForeignCurrencyRateCheck.PAY_013
    }

    data class RateRejected(
        val reason: RateReason,
    ) : RateVerdict {
        val code: String get() = ForeignCurrencyRateCheck.PAY_006
    }

    data class Passed(
        val rateUsed: RateOnFile,
    ) : RateVerdict
}

/**
 * The foreign-currency rate check (INV-REQ-510, 511, 468, 466, 467, 469, 1298; ruling INV-Q-6802 A): a pure function
 * over plain values. One total order, the first step that matches decides. The SGD total strings are only tested for
 * presence, never parsed or recomputed (INV-REQ-810).
 */
object ForeignCurrencyRateCheck {
    const val PAY_005 = "PAY-005"
    const val PAY_006 = "PAY-006"
    const val PAY_013 = "PAY-013"
    const val PAY_016 = "PAY-016"

    private const val SGD = "SGD"
    private const val PERCENT = "percent"
    private val HUNDRED = BigDecimal(100)

    // The seven-argument signature is fixed by ruling INV-Q-6802 A.
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongParameterList")
    fun check(
        currency: String,
        documentDate: LocalDate,
        elected: String?,
        onFile: RateOnFile?,
        stated: StatedSgd,
        packTolerance: RateTolerance,
        entityTolerancePercent: BigDecimal?,
    ): RateVerdict {
        require(currency.isNotBlank()) { "currency must not be blank" }
        if (currency == SGD) return RateVerdict.NotApplicable
        require(packTolerance.unit == PERCENT) { "packTolerance.unit must be percent" }
        require(
            entityTolerancePercent == null ||
                (entityTolerancePercent.signum() >= 0 && entityTolerancePercent <= packTolerance.value),
        ) { "entityTolerancePercent must be between 0 and the pack tolerance" }

        val missing =
            buildList {
                if (stated.exchangeRate == null) add("exchangeRate")
                if (stated.exchangeRateSource.isNullOrBlank()) add("exchangeRateSource")
                if (stated.sgdTotalExcludingTax.isNullOrBlank()) add("sgdTotalExcludingTax")
                if (stated.sgdTotalTax.isNullOrBlank()) add("sgdTotalTax")
                if (stated.sgdTotalIncludingTax.isNullOrBlank()) add("sgdTotalIncludingTax")
            }.sorted()
        if (missing.isNotEmpty()) return RateVerdict.MissingSgdFields(missing)

        if (elected == null) return RateVerdict.NoElection
        if (onFile == null || onFile.rate.signum() <= 0) return RateVerdict.NoRateOnFile
        require(onFile.source == elected) { "onFile source differs from the elected source" }
        require(onFile.currency == currency) { "onFile currency differs from the document currency" }
        require(onFile.date == documentDate) { "onFile date differs from the document date" }

        if (stated.exchangeRateSource != elected) return RateVerdict.RateRejected(RateReason.SOURCE_NOT_ELECTED)
        val rate = checkNotNull(stated.exchangeRate)
        if (rate.signum() <= 0) return RateVerdict.RateRejected(RateReason.STATED_RATE_NOT_POSITIVE)
        val tolerance = entityTolerancePercent ?: packTolerance.value
        if (rate.subtract(onFile.rate).abs().multiply(HUNDRED) > tolerance.multiply(onFile.rate)) {
            return RateVerdict.RateRejected(RateReason.OUTSIDE_TOLERANCE)
        }
        return RateVerdict.Passed(onFile)
    }
}
