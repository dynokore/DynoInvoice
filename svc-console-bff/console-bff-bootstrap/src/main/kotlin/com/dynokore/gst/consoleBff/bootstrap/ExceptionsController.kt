package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.application.exceptions.ListExceptions
import com.dynokore.gst.consoleBff.application.exceptions.ListExceptionsQuery
import com.dynokore.gst.consoleBff.application.exceptions.ListExceptionsResult
import com.dynokore.gst.consoleBff.application.exceptions.QueuedException
import com.dynokore.gst.consoleBff.contract.api.ExceptionsApi
import com.dynokore.gst.consoleBff.contract.model.ExceptionPage
import com.dynokore.gst.consoleBff.domain.badge.ItemState
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.gst.consoleBff.contract.model.Exception as ExceptionItem

/** A refusal carrying its closed problem answer; [ExceptionsProblemAdvice] writes it. */
class ExceptionsRefusal(
    val answer: StoredResponse,
) : RuntimeException(null, null, false, false)

/**
 * GET /v1/exceptions (INV-REQ-940): the page of [ListExceptions] with the stored members only. The route is
 * authorised by the read filter on `exception.view`; the entity scope is decided in the use case per request
 * (INV-Q-10027). A member the stored row does not hold is omitted, never guessed.
 */
@RestController
open class ExceptionsController(
    private val listExceptions: ListExceptions,
) : ExceptionsApi {
    override fun getExceptions(
        entityRef: String?,
        state: String?,
        deferred: Boolean,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<ExceptionPage> {
        val request = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request
        val principal = request.getAttribute(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE) as? String
        val tenant = request.getAttribute(ConsoleSessionFilter.TENANT_ATTRIBUTE) as? String
        if (principal == null || tenant == null) refuse(Problems.unauthenticated(correlation()))
        val itemState = state?.let { stateOf(it) }
        val query = ListExceptionsQuery(tenant, principal, entityRef, itemState, deferred, limit, cursor)
        return when (val result = listExceptions.list(query)) {
            is ListExceptionsResult.Listed -> {
                ResponseEntity.ok(ExceptionPage(result.items.map(::toItem), result.nextCursor))
            }

            is ListExceptionsResult.OutOfScope -> {
                refuse(Problems.accessDenied(correlation()))
            }

            ListExceptionsResult.BadCursor -> {
                refuse(Problems.contractViolation("cursor is not valid", correlation()))
            }

            ListExceptionsResult.BadLimit -> {
                refuse(Problems.contractViolation("limit is out of bounds", correlation()))
            }
        }
    }

    private fun refuse(answer: StoredResponse): Nothing = throw ExceptionsRefusal(answer)

    private fun stateOf(wire: String): ItemState =
        when (wire) {
            "OPEN" -> ItemState.OPEN
            "RESOLVED" -> ItemState.RESOLVED
            "OVERRIDDEN" -> ItemState.OVERRIDDEN
            else -> refuse(Problems.contractViolation("state is not a known value", correlation()))
        }

    private fun toItem(queued: QueuedException): ExceptionItem {
        val row = queued.row
        return ExceptionItem(
            exceptionId = row.exceptionId,
            entityRef = row.entityRef,
            code = row.code,
            state = ExceptionItem.State.forValue(row.state.name),
            raisedAt = row.raisedAt.atOffset(ZoneOffset.UTC),
            remediationOwner = row.remediationOwner,
            disposition = row.disposition,
            periodRef = row.periodRef.takeIf { it.isNotEmpty() },
            dueDate = queued.dueDate,
        )
    }

    private fun correlation(): String = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
}
