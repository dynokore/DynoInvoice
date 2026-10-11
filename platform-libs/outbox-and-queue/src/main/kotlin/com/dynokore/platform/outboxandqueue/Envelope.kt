package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.time.LocalDate

/**
 * The common event envelope of FR-K.3 / FR-M.11 in CloudEvents 1.0 terms, plus the
 * payload as JSON text (INV-REQ-1162).
 *
 * A tenantless platform event carries `TenantId.PLATFORM` and [EntityRef.PLATFORM] here (INV-Q-2927
 * option D); its CloudEvents form leaves `tenantid` and `entityref` out.
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
        require(tenant.isPlatform == entity.isPlatform) { PLATFORM_PAIR }
        require(!tenant.isPlatform || isPlatformType(type)) {
            "only a platform event type may be tenantless (FR-K.3, INV-Q-3018), was '$type'"
        }
    }

    /** The emitting service named by [source]. */
    val service: String get() = source.removePrefix("/gst/")

    companion object {
        private const val PLATFORM_TYPE_PREFIX = "com.dynokore.gst.platform."

        /** INV-REQ-1226 behaviour 1: the closed platform class. */
        private val PLATFORM_EVENTS =
            setOf(
                "AuditEntryRecorded",
                "JobRunRecorded",
                "JobRegistered",
                "ConnectionStatusChanged",
                "EvidenceArtefactRecorded",
                "PolicyChanged",
                "PackReadinessAcknowledged",
            )

        /**
         * True for a platform event type (`com.dynokore.gst.platform.<Name>.v<N>` with `<Name>` one of
         * INV-REQ-1226's seven platform events): the
         * only types that may be written under `TenantId.PLATFORM` and travel without `tenantid` and
         * `entityref` (INV-Q-3018). A domain event always carries a real tenant (FR-K.3, INV-REQ-1162).
         */
        fun isPlatformType(type: String): Boolean = type.startsWith(PLATFORM_TYPE_PREFIX) &&
            type.removePrefix(PLATFORM_TYPE_PREFIX).substringBefore(".v") in PLATFORM_EVENTS

        private val SOURCE_PATTERN = Regex("^/gst/[a-z-]+$")
        private val TYPE_PATTERN = Regex("^com\\.dynokore\\.gst\\.[a-z-]+\\.[A-Za-z]+\\.v[0-9]+$")
    }
}
