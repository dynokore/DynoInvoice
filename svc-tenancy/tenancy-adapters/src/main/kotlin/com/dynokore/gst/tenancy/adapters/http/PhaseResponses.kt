package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.status.PhaseAssessmentRequest
import com.dynokore.gst.tenancy.application.status.ReturnFigures
import com.dynokore.gst.tenancy.application.status.StoredAssessment
import com.dynokore.gst.tenancy.contract.model.AssessPhaseRequest
import com.dynokore.gst.tenancy.contract.model.PhaseAssessmentResponse
import com.dynokore.gst.tenancy.contract.model.PhaseAssessmentResponseInputs
import com.dynokore.gst.tenancy.contract.model.PhaseAssessmentReturn

internal fun phaseRequestOf(request: AssessPhaseRequest) =
    PhaseAssessmentRequest(
        request.asOfDate,
        request.box4Figures,
        request.returns?.map { ReturnFigures(it.periodStart, it.periodEnd, it.box4Figures) },
        request.extrapolate ?: false,
    )

/** The response carries the result and every input used, exactly as stored (INV-REQ-334 behaviour 5). */
internal fun phaseResponse(
    entityRef: String,
    stored: StoredAssessment,
): PhaseAssessmentResponse {
    val result = stored.assessment
    val inputs = result.inputs
    return PhaseAssessmentResponse(
        entityRef,
        PhaseAssessmentResponse.Phase.valueOf(stored.phase.name),
        result.mandatoryDate,
        result.totalAnnualSupplies,
        PhaseAssessmentResponseInputs(
            inputs.window?.first?.atDay(1),
            inputs.window?.last?.atEndOfMonth(),
            inputs.daysCovered?.toInt(),
            inputs.extrapolationChosen,
            inputs.summedBox4,
            inputs.returnsUsed.takeIf { it.isNotEmpty() }?.map {
                PhaseAssessmentReturn(it.periodStart, it.periodEnd, it.box4)
            },
        ),
    )
}
