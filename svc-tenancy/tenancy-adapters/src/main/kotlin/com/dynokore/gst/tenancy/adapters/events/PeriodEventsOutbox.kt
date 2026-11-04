package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.application.period.DueDateCause
import com.dynokore.gst.tenancy.application.period.ExtensionScopeKind
import com.dynokore.gst.tenancy.application.period.PeriodDueDateChange
import com.dynokore.gst.tenancy.application.period.PeriodEventPublisher
import com.dynokore.gst.tenancy.application.period.PeriodExtension
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * TPL-05: maps a recorded extension to PeriodExtended and a changed filing due date to PeriodDueDateChanged, appended
 * through outbox-and-queue in the transaction that changes the data (CS-17). The payloads are the
 * `period-extended-payload` and `period-due-date-changed-payload` schemas of platform-contracts (INV-Q-7391).
 */
class PeriodEventsOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
    private val tenants: TenantContext,
) : PeriodEventPublisher {
    override fun extended(event: PeriodExtension) = append(EXTENDED_TYPE, event.entityRef, extendedPayload(event))

    override fun dueDateChanged(change: PeriodDueDateChange) =
        append(DUE_DATE_CHANGED_TYPE, change.entityRef, dueDatePayload(change))

    private fun append(
        type: String,
        entityRef: String,
        payload: ObjectNode,
    ) {
        val context = EventContexts.forAggregate(tenants.current(), EntityRef(entityRef), entityRef)
        outbox.append(listOf(OutboundEvent(type, payload.toString())), context)
    }

    internal fun extendedPayload(event: PeriodExtension): ObjectNode {
        val request = event.request
        val root = mapper.createObjectNode()
        root.put("entityRef", event.entityRef)
        root.put("period", event.periodRef)
        root.put("target", request.target.name)
        request.newFilingDueDate?.let { root.put("newFilingDueDate", it.toString()) }
        request.newSubmission?.let { root.put("newSubmissionDueDate", it.toString()) }
        val scope = root.putObject("scope")
        scope.put("kind", request.scope.kind.name)
        if (request.scope.kind == ExtensionScopeKind.DOCUMENT_SET) {
            val refs = scope.putArray("documentRefs")
            request.scope.documentRefs.forEach { refs.add(it) }
        }
        if (request.scope.kind == ExtensionScopeKind.DATE_RANGE) {
            scope.put("dateFrom", checkNotNull(request.scope.dateFrom).toString())
            scope.put("dateTo", checkNotNull(request.scope.dateTo).toString())
        }
        root.put("irasReference", request.irasReference)
        return root
    }

    internal fun dueDatePayload(change: PeriodDueDateChange): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("entityRef", change.entityRef)
        root.put("period", change.periodRef)
        root.put("oldFilingDueDate", change.oldFilingDueDate.toString())
        root.put("newFilingDueDate", change.newFilingDueDate.toString())
        val cause = root.putObject("cause")
        when (val c = change.cause) {
            is DueDateCause.Extension -> {
                cause.put("irasReference", c.irasReference)
            }

            is DueDateCause.Pack -> {
                cause.put("packIdentifier", c.packIdentifier)
                cause.put("packVersion", c.packVersion)
                cause.put("effectiveDate", c.effectiveDate.toString())
            }

            is DueDateCause.VariationPoint -> {
                cause.put("variationPoint", c.variationPoint)
                cause.put("effectiveDate", c.effectiveDate.toString())
            }
        }
        return root
    }

    companion object {
        /** The asyncapi channels this class publishes (operations.tsv lists them by message name). */
        const val EXTENDED_CHANNEL = "PeriodExtended"
        const val DUE_DATE_CHANGED_CHANNEL = "PeriodDueDateChanged"

        /** The message `name` of each channel in the tenancy asyncapi document. */
        const val EXTENDED_TYPE = "com.dynokore.gst.tenancy.PeriodExtended.v1"
        const val DUE_DATE_CHANGED_TYPE = "com.dynokore.gst.tenancy.PeriodDueDateChanged.v1"
    }
}
