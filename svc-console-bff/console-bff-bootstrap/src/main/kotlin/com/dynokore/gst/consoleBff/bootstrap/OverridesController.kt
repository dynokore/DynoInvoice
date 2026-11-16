package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.session.ConsoleSessionFilter
import com.dynokore.gst.consoleBff.application.override.OverrideException
import com.dynokore.gst.consoleBff.application.override.OverrideOutcome
import com.dynokore.gst.consoleBff.contract.api.ExceptionsApi
import com.dynokore.gst.consoleBff.contract.model.OverrideRequest
import com.dynokore.gst.consoleBff.contract.model.OverrideResult
import com.dynokore.gst.consoleBff.contract.model.PendingOverride
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.ZoneOffset
import java.util.UUID

/** A refusal carrying its closed or relayed answer; [OverridesProblemAdvice] writes it. */
class OverridesRefusal(
    val answer: StoredResponse,
) : RuntimeException(null, null, false, false)

/**
 * overrideException (INV-REQ-500): routes the override through [OverrideException]; it decides the permission
 * `validation.override` per request (INV-Q-10027) and this class compares no role. The exception row is never
 * written here: it moves to OVERRIDDEN only from the owning service's event (single writer per state).
 */
@RestController
open class OverridesController(
    private val overrideException: OverrideException,
    private val problems: OverridesProblemMapper,
) : ExceptionsApi {
    override fun overrideException(
        id: UUID,
        overrideRequest: OverrideRequest,
    ): ResponseEntity<OverrideResult> {
        val request = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request
        val principal = request.getAttribute(ConsoleSessionFilter.PRINCIPAL_ATTRIBUTE) as? String
        val tenant = request.getAttribute(ConsoleSessionFilter.TENANT_ATTRIBUTE) as? String
        if (principal == null || tenant == null) refuse(problems.unauthenticated(correlation()))
        val reason = overrideRequest.reason
        if (reason.isBlank()) refuse(problems.contractViolation("reason is required", correlation()))
        val exceptionId = id.toString()
        return when (val outcome = overrideException.execute(tenant, principal, exceptionId, reason)) {
            is OverrideOutcome.Overridden -> {
                ResponseEntity.ok(
                    OverrideResult(
                        exceptionId = id,
                        state = OverrideResult.State.forValue("OVERRIDDEN"),
                        reason = reason,
                        overriddenAt = outcome.overriddenAt.atOffset(ZoneOffset.UTC),
                    ),
                )
            }

            is OverrideOutcome.PendingApproval -> {
                accepted(
                    PendingOverride(
                        exceptionId = id,
                        reason = reason,
                        requestedAt = outcome.requestedAt.atOffset(ZoneOffset.UTC),
                        status = PendingOverride.Status.forValue("PENDING"),
                    ),
                )
            }

            is OverrideOutcome.Relayed -> {
                refuse(problems.relayed(outcome.refusal, correlation()))
            }

            OverrideOutcome.NotFound -> {
                refuse(problems.notFound(correlation()))
            }

            OverrideOutcome.Forbidden -> {
                refuse(problems.accessDenied(correlation()))
            }

            is OverrideOutcome.Unavailable -> {
                refuse(problems.unavailable(correlation()))
            }
        }
    }

    /** The generated signature names the 200 body only; the 202 body is another schema, so the type is erased here. */
    @Suppress("UNCHECKED_CAST")
    private fun accepted(body: PendingOverride): ResponseEntity<OverrideResult> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(body) as ResponseEntity<OverrideResult>

    private fun refuse(answer: StoredResponse): Nothing = throw OverridesRefusal(answer)

    private fun correlation(): String = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
}
