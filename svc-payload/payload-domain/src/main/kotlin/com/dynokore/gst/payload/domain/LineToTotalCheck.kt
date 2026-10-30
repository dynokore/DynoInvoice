package com.dynokore.gst.payload.domain

import java.math.BigDecimal

/** The scope-pack line-to-total tolerance datum (INV-REQ-1297); the only unit is [LineToTotalCheck.AMOUNT]. */
data class PackLineTolerance(
    val defaultValue: BigDecimal,
    val ceiling: BigDecimal,
    val unit: String,
)

/** The verdict of [LineToTotalCheck.check]; a variance is a warning and never a block (INV-REQ-474). */
sealed interface LineToTotalVerdict {
    data object WithinTolerance : LineToTotalVerdict

    data class Variance(
        val lineSum: BigDecimal,
        val statedTotal: BigDecimal,
        val variance: BigDecimal,
        val tolerance: BigDecimal,
    ) : LineToTotalVerdict {
        val code: String get() = LineToTotalCheck.PAY_004
        val severity: Severity get() = Severity.WARN
    }
}

/** Decides PAY-004 (INV-REQ-474, INV-REQ-810, INV-REQ-1297); the stated total is never recomputed. */
object LineToTotalCheck {
    const val PAY_004 = "PAY-004"
    const val AMOUNT = "amount"

    fun check(
        lineAmounts: List<BigDecimal>,
        statedTotal: BigDecimal,
        pack: PackLineTolerance,
        tenantValue: BigDecimal?,
    ): LineToTotalVerdict {
        require(pack.unit == AMOUNT) { "unsupported line-to-total unit: '${pack.unit}'" }
        require(pack.defaultValue.signum() >= 0) { "pack default must not be negative" }
        require(pack.defaultValue.compareTo(pack.ceiling) <= 0) { "pack default must not exceed the ceiling" }
        require(tenantValue == null || (tenantValue.signum() >= 0 && tenantValue.compareTo(pack.ceiling) <= 0)) {
            "tenant value must be within 0 and the pack ceiling"
        }
        val tolerance = tenantValue ?: pack.defaultValue
        val lineSum = lineAmounts.fold(BigDecimal.ZERO) { acc, amount -> acc.add(amount) }
        val variance = lineSum.subtract(statedTotal).abs()
        return if (variance.compareTo(tolerance) > 0) {
            LineToTotalVerdict.Variance(lineSum, statedTotal, variance, tolerance)
        } else {
            LineToTotalVerdict.WithinTolerance
        }
    }
}
