package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.breakglass.ReportBreakGlassActionUseCase
import com.dynokore.gst.access.application.breakglass.ReportBreakGlassOutcome
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * INV-Q-9117 A, INV-Q-9313 A: records a break-glass action that tenancy-svc reports as the platform event
 * `BreakGlassActionReported`. An invalid payload is a [ContractViolation] (poison). The emitting service must be one
 * of the producers the access contract declares; it is stored as the calling service and never read from the payload.
 * The actor is the workload principal the inbox binds, since the report carries no human actor. A refused or repeated
 * report is acknowledged (IGNORED); the use case counts and logs it.
 */
class BreakGlassActionReportedHandler(
    private val useCase: ReportBreakGlassActionUseCase,
    private val mapper: ObjectMapper,
    private val tenants: TenantContext,
    private val producers: Set<String>,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        if (event.type != TYPE) violation("this handler takes $TYPE, not ${event.type}")
        if (event.service !in producers) violation("${event.service} is not a producer of $TYPE")
        val payload = mapper.readTree(event.dataJson)
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound: the inbox binds the workload principal" }
        val outcome =
            useCase.handle(
                actor = actor,
                reportId = payload.reference("reportId"),
                unlockRef = payload.reference("unlockRef"),
                action =
                    payload.get("action")?.takeIf { it.isString }?.asString() ?: violation("action is not a string"),
                target = payload.optionalTarget(),
                occurredAt = payload.occurredAt(),
                callerService = event.service,
            )
        return when (outcome) {
            is ReportBreakGlassOutcome.Stored -> HandleOutcome.APPLIED
            ReportBreakGlassOutcome.Duplicate -> HandleOutcome.IGNORED
            is ReportBreakGlassOutcome.Refused -> HandleOutcome.IGNORED
        }
    }

    private fun JsonNode?.reference(field: String): String {
        val text = this?.get(field)?.takeIf { it.isString }?.asString()
        if (text == null || text.isBlank() || text.length > MAX_REFERENCE) {
            violation("$field is missing, blank, not a string or longer than $MAX_REFERENCE")
        }
        return text
    }

    private fun JsonNode.optionalTarget(): String? {
        val node = get("target") ?: return null
        val text = node.takeIf { it.isString }?.asString()
        if (text.isNullOrEmpty() || text.length > MAX_TARGET) {
            violation("target is not a string of 1 to $MAX_TARGET characters")
        }
        return text
    }

    private fun JsonNode.occurredAt(): Instant {
        val text = get("occurredAt")?.takeIf { it.isString }?.asString() ?: violation("occurredAt is not a string")
        return try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            throw ContractViolation("occurredAt is not an ISO-8601 instant", e)
        }
    }

    private fun violation(message: String): Nothing = throw ContractViolation(message)

    companion object {
        const val TYPE = "com.dynokore.gst.platform.BreakGlassActionReported.v1"
        private const val MAX_REFERENCE = 64
        private const val MAX_TARGET = 128
    }
}
