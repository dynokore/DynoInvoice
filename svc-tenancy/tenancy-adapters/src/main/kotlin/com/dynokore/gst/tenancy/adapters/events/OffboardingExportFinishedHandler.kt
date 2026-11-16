package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.application.offboarding.CompleteOffboardingExport
import com.dynokore.gst.tenancy.application.offboarding.ExportResultOutcome
import com.dynokore.gst.tenancy.application.offboarding.OffboardingExportResults
import com.dynokore.gst.tenancy.domain.ExportContent
import com.dynokore.gst.tenancy.domain.ExportManifest
import com.dynokore.gst.tenancy.domain.ExportState
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The consumer of `gst.evidence.offboarding-export-finished.v1` (INV-REQ-877, INV-AC-22960): moves the REQUESTED
 * offboarding record named by the payload to COMPLETED or FAILED through [CompleteOffboardingExport], in the consumer
 * transaction. The envelope arrives under the real tenant and the tenant-wide entity; the handler keys by the envelope
 * tenant and never reads an entity. An unknown record, a payload member that is missing or unknown, or a `tenantRef`
 * that differs from the envelope tenant is a [ContractViolation] and ends in the dead letter; a record that is no
 * longer REQUESTED is [HandleOutcome.IGNORED], so a redelivery changes nothing.
 */
class OffboardingExportFinishedHandler(
    private val complete: CompleteOffboardingExport,
    private val mapper: ObjectMapper,
    private val results: OffboardingExportResults,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = parse(event.dataJson)
        val tenantRef = text(data, "tenantRef")
        val offboardingRef = text(data, "offboardingRef")
        val exportRef = text(data, "exportRef")
        val state = text(data, "state")
        val manifest =
            when (state) {
                "READY" -> ExportManifest(exportRef, ExportState.READY, contents(data))
                "FAILED" -> ExportManifest(exportRef, ExportState.FAILED, emptyList())
                else -> violation("state is neither READY nor FAILED")
            }
        val reason = if (state == "FAILED") failureReason(data) else null
        // the record is read on the tenant-bound session of the envelope tenant, so its tenantRef is that tenant's ref
        val stored = results.find(offboardingRef) ?: violation("no offboarding record $offboardingRef")
        if (stored.record.tenantRef != tenantRef) violation("tenantRef differs from the envelope tenant")
        return when (complete.apply(offboardingRef, manifest, reason)) {
            ExportResultOutcome.APPLIED, ExportResultOutcome.PENDING -> HandleOutcome.APPLIED
            ExportResultOutcome.IGNORED -> HandleOutcome.IGNORED
            ExportResultOutcome.NOT_FOUND -> violation("no offboarding record $offboardingRef")
        }
    }

    private fun contents(data: JsonNode): List<ExportContent> {
        val items = data["contents"]?.takeIf { it.isArray } ?: violation("contents is missing")
        val names =
            items.values().map { item ->
                item.takeIf { it.isString }?.asString() ?: violation("a contents member is not a string")
            }
        if (names.toSet().size != names.size) violation("contents repeats a member")
        return names.map { name ->
            ExportContent.entries.firstOrNull { it.name == name } ?: violation("unknown content $name")
        }
    }

    private fun failureReason(data: JsonNode): String {
        val reason = text(data, "failureReason")
        if (reason.length > REASON_MAX) violation("failureReason is longer than $REASON_MAX")
        return reason
    }

    private fun text(
        data: JsonNode,
        name: String,
    ): String =
        data[name]?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }
            ?: violation("$name is missing or not a non-blank string")

    private fun parse(text: String): JsonNode =
        try {
            mapper.readTree(text)?.takeIf { it.isObject } ?: violation("the payload is not an object")
        } catch (e: JacksonException) {
            throw ContractViolation("the payload is not JSON", e)
        }

    private fun violation(message: String): Nothing = throw ContractViolation(message)

    private companion object {
        const val REASON_MAX = 500
    }
}
