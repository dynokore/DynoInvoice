package com.dynokore.gst.submission.adapters.http

import com.dynokore.gst.submission.application.filing.ConfirmFilingRequest
import com.dynokore.gst.submission.application.filing.ConfirmFilingResult
import com.dynokore.gst.submission.contract.model.FilingConfirmation
import com.dynokore.gst.submission.contract.model.FilingConfirmationConflict
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.PeriodErrorCode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The calendar date of a filing (INV-REQ-914): the contract carries an instant, the stored fact a civil date derived
 * in the configured business zone (ISO 8601; `inv.filing.business-zone`, coordinator ruling round z, 2026-11-12).
 */
object FilingBinding {
    /** The civil date of [instant] in [zone]. */
    fun toBusinessDate(
        instant: OffsetDateTime,
        zone: ZoneId,
    ): LocalDate = instant.atZoneSameInstant(zone).toLocalDate()

    /** The start of [date] in [zone], carrying that zone's offset on that date. */
    fun startOfDay(
        date: LocalDate,
        zone: ZoneId,
    ): OffsetDateTime = date.atStartOfDay(zone).toOffsetDateTime()

    /** The refusal a non-recorded [result] becomes; the use case decided it, the binding only names its code. */
    fun refusalOf(result: ConfirmFilingResult): RuntimeException =
        when (result) {
            is ConfirmFilingResult.Recorded -> IllegalArgumentException("a recorded filing is not a refusal")
            is ConfirmFilingResult.Forbidden -> ComplianceRefusal(ClosedErrorCode.ACCESS_DENIED)
            ConfirmFilingResult.NoDueDate -> ComplianceRefusal(ClosedErrorCode.NOT_FOUND)
            is ConfirmFilingResult.GateRefused -> FilingPeriodRefusal(PeriodErrorCode.PERIOD_NOT_READY)
        }

    /** The `FilingConfirmation`: dates are rendered with [startOfDay], never the request instant. */
    fun confirmation(
        call: ConfirmFilingRequest,
        portalReference: String?,
        result: ConfirmFilingResult.Recorded,
        zone: ZoneId,
    ) = FilingConfirmation(
        entityRef = call.entityRef,
        periodRef = call.periodRef,
        filedAt = startOfDay(call.filedAt, zone),
        irasAcknowledgementReference = call.acknowledgementRef,
        confirmedAt = result.confirmedAt.atOffset(ZoneOffset.UTC),
        portalReference = portalReference,
        governingFiledAt = startOfDay(result.governingFiledAt, zone),
        conflict =
            result.conflict?.let {
                FilingConfirmationConflict(it.code, startOfDay(it.otherFiledAt, zone))
            },
    )
}
