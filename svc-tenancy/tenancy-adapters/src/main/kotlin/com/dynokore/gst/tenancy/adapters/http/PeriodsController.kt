package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.period.ConfirmPeriods
import com.dynokore.gst.tenancy.contract.api.PeriodsApi
import com.dynokore.gst.tenancy.contract.model.ConfirmPeriodRequest
import com.dynokore.gst.tenancy.contract.model.PeriodResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * TPL-08. The `periods` operations of the tenancy contract this service implements: confirmPeriods (INV-T-4929). The
 * operations listPeriodRecords and recordExtension stay the generated 501 until their task (INV-T-4930).
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin)
open class PeriodsController(
    private val confirmPeriods: ConfirmPeriods,
) : PeriodsApi {
    override fun confirmPeriods(
        entityRef: String,
        idempotencyKey: UUID,
        confirmPeriodRequest: ConfirmPeriodRequest,
    ): ResponseEntity<PeriodResponse> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(confirmationOf(entityRef, confirmPeriods.confirm(entityRef, confirmRequestOf(confirmPeriodRequest))))
}
