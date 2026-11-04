package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.period.ConfirmPeriods
import com.dynokore.gst.tenancy.application.period.ListPeriodRecords
import com.dynokore.gst.tenancy.application.period.PagePosition
import com.dynokore.gst.tenancy.application.period.RecordExtension
import com.dynokore.gst.tenancy.application.period.SINGAPORE
import com.dynokore.gst.tenancy.contract.api.PeriodsApi
import com.dynokore.gst.tenancy.contract.model.ConfirmPeriodRequest
import com.dynokore.gst.tenancy.contract.model.PeriodExtensionRequest
import com.dynokore.gst.tenancy.contract.model.PeriodExtensionResponse
import com.dynokore.gst.tenancy.contract.model.PeriodRecordListResponse
import com.dynokore.gst.tenancy.contract.model.PeriodResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * TPL-08. The `periods` operations of the tenancy contract this service implements: confirmPeriods (INV-T-4929),
 * recordExtension and listPeriodRecords (INV-T-4930). The contract has no operation that edits a due date: a PATCH or
 * a PUT of a period is a route that does not exist and is refused by the framework (INV-REQ-81).
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin)
open class PeriodsController(
    private val confirmPeriods: ConfirmPeriods,
    private val recordExtension: RecordExtension,
    private val listPeriodRecords: ListPeriodRecords,
    private val cursors: PeriodListCursor,
    private val caller: CallerSource,
    private val clock: ObjectProvider<Clock>,
) : PeriodsApi {
    override fun confirmPeriods(
        entityRef: String,
        idempotencyKey: UUID,
        confirmPeriodRequest: ConfirmPeriodRequest,
    ): ResponseEntity<PeriodResponse> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(confirmationOf(entityRef, confirmPeriods.confirm(entityRef, confirmRequestOf(confirmPeriodRequest))))

    override fun recordExtension(
        entityRef: String,
        periodRef: String,
        idempotencyKey: UUID,
        periodExtensionRequest: PeriodExtensionRequest,
    ): ResponseEntity<PeriodExtensionResponse> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(
                extensionOf(
                    recordExtension.record(entityRef, periodRef, extensionRequestOf(periodExtensionRequest)),
                ),
            )

    override fun listPeriodRecords(
        entityRef: String,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<PeriodRecordListResponse> {
        if (limit < 1 || limit > MAX_LIMIT) invalid("limit is between 1 and $MAX_LIMIT")
        val now = clock.getIfAvailable(Clock::systemUTC)
        val user = caller.actor()
        val after = cursor?.let { cursors.open(it, entityRef, user, now.instant()) }
        val today = LocalDate.now(now.withZone(SINGAPORE))
        val outcome = listPeriodRecords.list(entityRef, limit, today, after)
        return ResponseEntity.ok(
            periodListOf(outcome) { last ->
                cursors.issue(entityRef, user, PagePosition(last.start, last.periodRef), now.instant())
            },
        )
    }

    private companion object {
        /** The bound the contract publishes for `limit` (API-18). */
        const val MAX_LIMIT = 500
    }
}
