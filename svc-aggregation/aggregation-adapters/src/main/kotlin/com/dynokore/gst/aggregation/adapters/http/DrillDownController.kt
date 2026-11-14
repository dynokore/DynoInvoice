package com.dynokore.gst.aggregation.adapters.http

import com.dynokore.gst.aggregation.application.DrillDownPage
import com.dynokore.gst.aggregation.application.DrillDownPosition
import com.dynokore.gst.aggregation.application.GetAggregateDrillDown
import com.dynokore.gst.aggregation.contract.api.DrillDownApi
import com.dynokore.gst.aggregation.contract.model.UnderlyingTransactionPage
import com.dynokore.gst.aggregation.contract.model.UnderlyingTransactionPageItemsInner
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

/**
 * The drill-down operation of the aggregation contract (TPL-08, INV-REQ-414, INV-REQ-884): the retained underlying
 * transactions of one aggregate in pages of the documented total order. The items carry a claimant reference and no
 * claimant name. `limit` above 500 or below 1, a cursor that is not valid for this principal and aggregate, and an
 * aggregateId that is not an identifier answer 400 (API-18); an aggregate the tenant does not hold answers 404.
 */
@RestController
open class DrillDownController(
    private val drillDown: GetAggregateDrillDown,
    private val cursors: DrillDownCursors,
    private val tenants: TenantContext,
) : DrillDownApi {
    override fun getAggregateDrillDown(
        aggregateId: String,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<UnderlyingTransactionPage> {
        val id = runCatching { UUID.fromString(aggregateId) }.getOrNull()
        val scope =
            DrillDownCursors.Scope(tenants.current().toString(), tenants.currentActor().orEmpty(), aggregateId)
        val after = cursor?.let { cursors.decode(it, scope) }
        return when {
            id == null -> {
                refused(ClosedErrorCode.CONTRACT_VIOLATION, "aggregateId is not an identifier")
            }

            limit !in 1..MAX_LIMIT -> {
                refused(ClosedErrorCode.CONTRACT_VIOLATION, "limit is outside 1 to $MAX_LIMIT")
            }

            cursor != null && after == null -> {
                refused(ClosedErrorCode.CONTRACT_VIOLATION, "the cursor is not valid here")
            }

            else -> {
                drillDown.execute(id, after, limit)?.let { ResponseEntity.ok(body(it, scope)) }
                    ?: refused(ClosedErrorCode.NOT_FOUND, null)
            }
        }
    }

    private fun body(
        page: DrillDownPage,
        scope: DrillDownCursors.Scope,
    ) = UnderlyingTransactionPage(
        aggregateId = page.aggregateId.toString(),
        items =
            page.items.map {
                UnderlyingTransactionPageItemsInner(
                    underlyingRef = it.underlyingRef,
                    transactionRef = it.transactionRef,
                    amount = BigDecimal(it.amount),
                    claimantReference = it.claimantReference,
                )
            },
        underlyingCount = page.underlyingCount,
        isCreditNote = page.isCreditNote,
        isSupplementary = page.isSupplementary,
        nextCursor = page.next?.let { cursors.encode(it, scope) },
    )

    /** The API-20 envelope of [code], written as the body of the operation's generic response. */
    @Suppress("UNCHECKED_CAST")
    private fun refused(
        code: ClosedErrorCode,
        detail: String?,
    ): ResponseEntity<UnderlyingTransactionPage> {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val rendered = Problems.render(code, correlation, detail)
        return ResponseEntity
            .status(HttpStatus.valueOf(rendered.status))
            .headers { headers -> rendered.headers.forEach { (name, value) -> headers.set(name, value) } }
            .body(rendered.body) as ResponseEntity<UnderlyingTransactionPage>
    }

    private companion object {
        const val MAX_LIMIT = 500
    }
}
