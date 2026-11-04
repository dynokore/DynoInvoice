package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.period.ConfirmKind
import com.dynokore.gst.tenancy.application.period.ConfirmOutcome
import com.dynokore.gst.tenancy.application.period.ConfirmRequest
import com.dynokore.gst.tenancy.application.period.ExtensionOutcome
import com.dynokore.gst.tenancy.application.period.ExtensionRequest
import com.dynokore.gst.tenancy.application.period.ExtensionScope
import com.dynokore.gst.tenancy.application.period.ExtensionScopeKind
import com.dynokore.gst.tenancy.application.period.ExtensionTarget
import com.dynokore.gst.tenancy.application.period.FrequencyOutcome
import com.dynokore.gst.tenancy.application.period.FrequencyRequest
import com.dynokore.gst.tenancy.application.period.FrequencyView
import com.dynokore.gst.tenancy.application.period.PeriodListOutcome
import com.dynokore.gst.tenancy.application.period.PeriodRecordView
import com.dynokore.gst.tenancy.contract.model.ConfirmPeriodRequest
import com.dynokore.gst.tenancy.contract.model.FilingFrequencyChangeRequest
import com.dynokore.gst.tenancy.contract.model.FilingFrequencyResponse
import com.dynokore.gst.tenancy.contract.model.FilingFrequencyResponseConfirmationPeriod
import com.dynokore.gst.tenancy.contract.model.PeriodExtensionRequest
import com.dynokore.gst.tenancy.contract.model.PeriodExtensionResponse
import com.dynokore.gst.tenancy.contract.model.PeriodRecord
import com.dynokore.gst.tenancy.contract.model.PeriodRecordListResponse
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

/** The request as the use case takes it: an absent target is FILING_DUE_DATE and an absent scope is PERIOD. */
internal fun extensionRequestOf(request: PeriodExtensionRequest) =
    ExtensionRequest(
        request.target?.let { ExtensionTarget.valueOf(it.name) } ?: ExtensionTarget.FILING_DUE_DATE,
        request.newDueDate,
        request.newSubmissionDueDate,
        request.scope?.let {
            ExtensionScope(
                ExtensionScopeKind.valueOf(it.kind.name),
                it.documentRefs.orEmpty().map { id -> id.toString() },
                it.dateFrom,
                it.dateTo,
            )
        } ?: ExtensionScope(ExtensionScopeKind.PERIOD),
        request.irasReference,
        request.evidenceRef,
    )

internal fun extensionOf(outcome: ExtensionOutcome): PeriodExtensionResponse =
    when (outcome) {
        is ExtensionOutcome.Done -> {
            PeriodExtensionResponse(
                outcome.entityRef,
                outcome.periodRef,
                outcome.request.newDueDate,
                PeriodExtensionResponse.Target.valueOf(outcome.request.target.name),
                outcome.request.newSubmissionDueDate,
            )
        }

        ExtensionOutcome.NotFound -> {
            notFound()
        }

        is ExtensionOutcome.Forbidden -> {
            forbidden(outcome.reason)
        }

        is ExtensionOutcome.Invalid -> {
            invalid(outcome.reason)
        }

        is ExtensionOutcome.Refused -> {
            constraintRefused(outcome.reason)
        }
    }

/** The records as the contract publishes them; `filingDate` is absent without a filing fact. */
internal fun periodListOf(
    outcome: PeriodListOutcome,
    nextCursor: (PeriodRecordView) -> String,
): PeriodRecordListResponse =
    when (outcome) {
        is PeriodListOutcome.Done -> {
            PeriodRecordListResponse(
                outcome.entityRef,
                outcome.items.map {
                    PeriodRecord(
                        it.periodRef,
                        it.start,
                        it.end,
                        PeriodRecord.Position.valueOf(it.position.name),
                        PeriodRecord.State.valueOf(it.state.name),
                        it.filingDueDate,
                        it.extensionGranted,
                        it.filingDate,
                    )
                },
                if (outcome.more) nextCursor(outcome.items.last()) else null,
            )
        }

        PeriodListOutcome.NotFound -> {
            notFound()
        }
    }
