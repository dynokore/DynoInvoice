package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.masterdata.application.rate.ImportRatesOutcome
import com.dynokore.gst.masterdata.application.rate.RateRowRejection
import org.springframework.http.HttpStatus

/**
 * INV-T-18775 (INV-T-18916): the wire codes of `importRates`. Both mappings are total `when` expressions over closed
 * types with no `else` branch, so a new member fails to compile; every code is a constant of the pinned gst-catalogue's
 * [ErrorCode] (CS-14), never a string typed here.
 */
object RateWireCodes {
    /** The refusal of an outcome that stored nothing, or null for [ImportRatesOutcome.Loaded]. */
    fun refusal(outcome: ImportRatesOutcome): CatalogueRefusal? =
        when (outcome) {
            is ImportRatesOutcome.Loaded -> {
                null
            }

            ImportRatesOutcome.SourceNotElected -> {
                CatalogueRefusal(
                    ErrorCode.MDM_012,
                    HttpStatus.UNPROCESSABLE_CONTENT.value(),
                )
            }

            ImportRatesOutcome.SourceNotApproved -> {
                CatalogueRefusal(
                    ErrorCode.MDM_013,
                    HttpStatus.UNPROCESSABLE_CONTENT.value(),
                )
            }

            ImportRatesOutcome.RowCountMismatch -> {
                CatalogueRefusal(ErrorCode.SRT_001, HttpStatus.BAD_REQUEST.value())
            }
        }

    /** The `code` of a rejected row of the `ImportRecord`. */
    fun rowCode(reason: RateRowRejection): ErrorCode =
        when (reason) {
            RateRowRejection.CURRENCY_INVALID -> ErrorCode.MDM_014
            RateRowRejection.RATE_INVALID -> ErrorCode.MDM_015
            RateRowRejection.REVISION_CONFLICT -> ErrorCode.SRT_008
        }
}
