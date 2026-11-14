package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.LaneLimits
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The events console-bff lists in its [EventCatalogue]: the canonical and master-data events it consumes, each equal
 * to its row of platform-contracts `event-log/topics.yaml`. console-bff emits no event today; the task that emits one
 * adds its entry.
 */
@Configuration(proxyBeanMethods = false)
class EventConfiguration {
    @Bean
    fun eventCatalogue(): EventCatalogue = EventCatalogue(ConsoleEvents.consumed)

    /**
     * No lane has a rate: no source names one and console-bff enqueues nothing today. service-runtime creates the one
     * `WorkQueue` from this bean; the task that owns a lane's rate adds its entry.
     */
    @Bean
    fun laneLimits(): LaneLimits = LaneLimits()
}

object ConsoleEvents {
    private const val CANONICAL = "canonical-svc"

    const val DOCUMENT_HELD_TYPE = "com.dynokore.gst.canonical.DocumentHeld.v1"
    const val DOCUMENT_HELD_TOPIC = "gst.canonical.document-held.v1"
    const val DOCUMENT_RELEASED_TYPE = "com.dynokore.gst.canonical.DocumentReleased.v1"
    const val DOCUMENT_RELEASED_TOPIC = "gst.canonical.document-released.v1"

    private const val MASTERDATA = "masterdata-svc"

    const val MASTER_DATA_RESOLVED_TYPE = "com.dynokore.gst.masterdata.MasterDataResolved.v1"
    const val MASTER_DATA_RESOLVED_TOPIC = "gst.masterdata.master-data-resolved.v1"

    private const val TENANCY = "tenancy-svc"

    const val PERIOD_DUE_DATE_CHANGED_TYPE = "com.dynokore.gst.tenancy.PeriodDueDateChanged.v1"
    const val PERIOD_DUE_DATE_CHANGED_TOPIC = "gst.tenancy.period-due-date-changed.v1"

    const val PERIOD_RECORDS_CHANGED_TYPE = "com.dynokore.gst.tenancy.PeriodRecordsChanged.v1"
    const val PERIOD_RECORDS_CHANGED_TOPIC = "gst.tenancy.period-records-changed.v1"

    private const val PAYLOAD = "payload-svc"
    private const val SUBMISSION = "submission-svc"

    const val PAYLOAD_REJECTED_TYPE = "com.dynokore.gst.payload.PayloadRejected.v1"
    const val PAYLOAD_REJECTED_TOPIC = "gst.payload.payload-rejected.v1"

    const val SUBMISSION_FAILED_TYPE = "com.dynokore.gst.submission.SubmissionFailed.v1"
    const val SUBMISSION_FAILED_TOPIC = "gst.submission.submission-failed.v1"

    val consumed: List<CatalogueEntry> =
        listOf(
            Triple(DOCUMENT_HELD_TYPE, DOCUMENT_HELD_TOPIC, CANONICAL),
            Triple(DOCUMENT_RELEASED_TYPE, DOCUMENT_RELEASED_TOPIC, CANONICAL),
            Triple(MASTER_DATA_RESOLVED_TYPE, MASTER_DATA_RESOLVED_TOPIC, MASTERDATA),
            Triple(PERIOD_DUE_DATE_CHANGED_TYPE, PERIOD_DUE_DATE_CHANGED_TOPIC, TENANCY),
            Triple(PERIOD_RECORDS_CHANGED_TYPE, PERIOD_RECORDS_CHANGED_TOPIC, TENANCY),
        ).map { (type, topic, producer) ->
            CatalogueEntry(
                type,
                topic,
                setOf(producer),
                contentTopic = false,
                recordTopic = false,
                retainedWhileResolvable = false,
            )
        } +
            CatalogueEntry(
                PAYLOAD_REJECTED_TYPE,
                PAYLOAD_REJECTED_TOPIC,
                setOf(PAYLOAD),
                contentTopic = true,
                recordTopic = false,
                retainedWhileResolvable = false,
            ) +
            CatalogueEntry(
                SUBMISSION_FAILED_TYPE,
                SUBMISSION_FAILED_TOPIC,
                setOf(SUBMISSION),
                contentTopic = false,
                recordTopic = false,
                retainedWhileResolvable = false,
            )
}
