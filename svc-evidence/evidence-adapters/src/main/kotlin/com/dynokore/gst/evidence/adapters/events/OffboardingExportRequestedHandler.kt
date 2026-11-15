package com.dynokore.gst.evidence.adapters.events

import com.dynokore.gst.evidence.application.export.ExportRequest
import com.dynokore.gst.evidence.application.export.ExportScope
import com.dynokore.gst.evidence.application.export.ExportTenantEvidence
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The consumer of `gst.tenancy.offboarding-export-requested.v1` (INV-REQ-877): builds the [ExportRequest] from the
 * payload and the envelope id and runs [ExportTenantEvidence], which registers the job REQUESTED (idempotent on the
 * offboarding reference) and runs it. It runs in the inbox consumer's transaction, so the job row, the outbox row of
 * the result and the inbox row commit together; an unexpected failure rolls all of them back.
 *
 * A payload that is not JSON, lacks `tenantRef`, `offboardingRef` or `exportRef`, names an unknown `scope`, or whose
 * `tenantRef` differs from the envelope tenant, is refused with [ContractViolation]: a poison record that writes
 * nothing.
 */
class OffboardingExportRequestedHandler(
    private val useCase: ExportTenantEvidence,
) : EventHandler {
    private val json = JsonMapper.builder().build()

    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val offboardingRef = required(data, "offboardingRef")
        val exportRef = required(data, "exportRef")
        if (required(data, "tenantRef") != event.tenant.toString()) {
            throw ContractViolation("tenantRef differs from the envelope tenant")
        }
        useCase.execute(request(event, data, offboardingRef, exportRef))
        return HandleOutcome.APPLIED
    }

    private fun request(
        event: Envelope,
        data: JsonNode,
        offboardingRef: String,
        exportRef: String,
    ): ExportRequest {
        val scope =
            ExportScope.entries.firstOrNull { it.name == text(data, "scope") }
                ?: throw ContractViolation("scope is missing or unknown")
        return try {
            ExportRequest(event.tenant, offboardingRef, exportRef, scope, text(data, "entityRef"), event.id.value)
        } catch (e: IllegalArgumentException) {
            throw ContractViolation("the request is inconsistent: ${e.message}", e)
        }
    }

    private fun required(
        data: JsonNode,
        name: String,
    ): String = text(data, name) ?: throw ContractViolation("$name is missing or blank")

    private fun parse(text: String): JsonNode =
        try {
            json.readTree(text)
        } catch (e: tools.jackson.core.JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private fun text(
        data: JsonNode,
        name: String,
    ): String? = data.get(name)?.takeIf { it.isString && it.asString().isNotBlank() }?.asString()
}
