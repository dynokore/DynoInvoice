package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.period.ConfirmKind
import com.dynokore.gst.tenancy.application.period.ConfirmOutcome
import com.dynokore.gst.tenancy.application.period.ConfirmRequest
import com.dynokore.gst.tenancy.application.period.FrequencyOutcome
import com.dynokore.gst.tenancy.application.period.FrequencyRequest
import com.dynokore.gst.tenancy.application.period.FrequencyView
import com.dynokore.gst.tenancy.contract.model.ConfirmPeriodRequest
import com.dynokore.gst.tenancy.contract.model.FilingFrequencyChangeRequest
import com.dynokore.gst.tenancy.contract.model.FilingFrequencyResponse
import com.dynokore.gst.tenancy.contract.model.FilingFrequencyResponseConfirmationPeriod
import com.dynokore.gst.tenancy.contract.model.PeriodResponse

internal fun frequencyRequestOf(request: FilingFrequencyChangeRequest) =
    FrequencyRequest(request.newFrequency.value, request.effectiveFrom, request.irasApprovalReference)

internal fun confirmRequestOf(request: ConfirmPeriodRequest) =
    ConfirmRequest(
        request.periodRef,
        request.periodStart,
        request.periodEnd,
        ConfirmKind.valueOf(request.kind.name),
    )

/** The recorded view as the contract response: a proposal presents its status, codes and confirmation period. */
internal fun frequencyResponse(
    entityRef: String,
    view: FrequencyView,
): FilingFrequencyResponse {
    val confirmation = view.confirmation?.takeIf { view.proposed }
    return FilingFrequencyResponse(
        entityRef,
        FilingFrequencyResponse.Frequency.forValue(view.entry.frequency),
        view.entry.effectiveFrom,
        if (view.proposed) {
            FilingFrequencyResponse.Status.PENDING_CONFIRMATION
        } else {
            FilingFrequencyResponse.Status.ACTIVE
        },
        view.raised.map { it.code }.takeIf { view.proposed },
        confirmation?.let {
            FilingFrequencyResponseConfirmationPeriod(
                it.periodRef,
                it.start,
                it.end,
                FilingFrequencyResponseConfirmationPeriod.Kind.valueOf(it.kind.name),
            )
        },
    )
}

internal fun frequencyOf(
    entityRef: String,
    outcome: FrequencyOutcome,
): FilingFrequencyResponse =
    when (outcome) {
        is FrequencyOutcome.Done -> frequencyResponse(entityRef, outcome.view)
        FrequencyOutcome.NotFound -> notFound()
        is FrequencyOutcome.Forbidden -> forbidden(outcome.reason)
        is FrequencyOutcome.Invalid -> invalid(outcome.reason)
        is FrequencyOutcome.Refused -> constraintRefused(outcome.reason)
    }

internal fun confirmationOf(
    entityRef: String,
    outcome: ConfirmOutcome,
): PeriodResponse =
    when (outcome) {
        is ConfirmOutcome.Done -> PeriodResponse(entityRef, outcome.periodRef, outcome.start, outcome.end)
        ConfirmOutcome.NotFound -> notFound()
        is ConfirmOutcome.Forbidden -> forbidden(outcome.reason)
        is ConfirmOutcome.Refused -> constraintRefused(outcome.reason)
    }
