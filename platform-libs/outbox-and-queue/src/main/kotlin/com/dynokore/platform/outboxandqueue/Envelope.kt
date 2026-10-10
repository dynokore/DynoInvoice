package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.time.LocalDate

/**
 * The common event envelope of FR-K.3 / FR-M.11 in CloudEvents 1.0 terms, plus the
 * payload as JSON text (INV-REQ-1162).
 *
 * @property id the idempotency key (CloudEvents `id`).
 * @property source `/gst/<emitting service>`; [instanceId] names the instance.
 * @property subject the aggregate key events are ordered under.
 * @property docUuid present when the event concerns a document.
 * @property docSequence per-aggregate monotonic sequence (FR-AH.10); absent when the event
 *   is not document-scoped.
 */
data class Envelope(
    val id: EventId,
    val source: String,
    val type: String,
    val subject: String,
    val time: Instant,
    val tenant: TenantId,
    val entity: EntityRef,
    val docUuid: DocUuid?,
    val transactionDate: LocalDate?,
    val docSequence: Long?,
    val instanceId: String,
    val correlation: CorrelationId,
    val causation: CausationId,
    val dataJson: String,
) {
    init {
        require(source.matches(SOURCE_PATTERN)) { "source must be /gst/<service>, was '$source'" }
        require(type.matches(TYPE_PATTERN)) { "event type must be com.dynokore.gst.<service>.<Name>.v<N>, was '$type'" }
        require(subject.isNotBlank()) { "subject must not be blank" }
        require(instanceId.isNotBlank()) { "instance id must not be blank" }
        require(docSequence == null || docSequence >= 1) { "docsequence starts at 1" }
    }

    /** The emitting service named by [source]. */
    val service: String get() = source.removePrefix("/gst/")

    private companion object {
        val SOURCE_PATTERN = Regex("^/gst/[a-z-]+$")
        val TYPE_PATTERN = Regex("^com\\.dynokore\\.gst\\.[a-z-]+\\.[A-Za-z]+\\.v[0-9]+$")
    }
}
