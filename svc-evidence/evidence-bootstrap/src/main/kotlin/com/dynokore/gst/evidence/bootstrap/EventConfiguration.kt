package com.dynokore.gst.evidence.bootstrap

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The events evidence-svc may emit (INV-REQ-815 behaviour 2): the three its AsyncAPI contract publishes,
 * EvidenceIntegrityFailed (model/architecture/events.yaml), PriorSubmissionEvidenceImported (INV-REQ-1109) and
 * ArchiveVolumeMeasured (INV-Q-9829 A, INV-Q-14067 A), each on the topic its channel names. The ExampleHappened
 * placeholder channel is not catalogued. The emission guard of the outbox refuses any other type.
 */
@Configuration(proxyBeanMethods = false)
class EventConfiguration {
    @Bean
    fun eventCatalogue(): EventCatalogue =
        EventCatalogue(
            EvidenceEvents.entries + EvidenceEvents.recordEntries + EvidenceEvents.returnEntries +
                EvidenceEvents.platformEntries + EvidenceEvents.acknowledgementEntries + EvidenceEvents.sealingEntries,
        )
}

object EvidenceEvents {
    private const val SERVICE = "evidence-svc"
    private const val TYPE_PREFIX = "com.dynokore.gst.evidence."

    /** Event type (the AsyncAPI message name) to topic (the channel address). */
    val topicByType: Map<String, String> =
        listOf(
            "EvidenceIntegrityFailed" to "gst.evidence.evidence-integrity-failed.v1",
            "PriorSubmissionEvidenceImported" to "gst.evidence.prior-submission-evidence-imported.v1",
            "ArchiveVolumeMeasured" to "gst.evidence.archive-volume-measured.v1",
        ).associate { (name, topic) -> "$TYPE_PREFIX$name.v1" to topic }

    /**
     * The platform events that have a topic (`PlatformEventCatalogue.ALL`, producer every service): the job runtime of
     * service-runtime writes `JobRegistered` and `JobRunRecorded` through the outbox, and the emission guard refuses
     * any type the catalogue lacks (INV-Q-12992 A, INV-Q-10922 A). `AuditEntryRecorded` is already a record entry, so
     * it is left out here, and `PolicyChanged` and `TenantWideEvidenceIntegrityFailed` are the hand-written entries of
     * [platformEntries] (the latter with evidence-svc as its only producer), and so is
     * `TenantWideArchiveVolumeMeasured`.
     */
    private val jobEntries: List<CatalogueEntry> =
        PlatformEventCatalogue.ALL
            .filterNot {
                it == PlatformEventCatalogue.AUDIT_ENTRY_RECORDED ||
                    it == PlatformEventCatalogue.POLICY_CHANGED ||
                    it == PlatformEventCatalogue.TENANT_WIDE_EVIDENCE_INTEGRITY_FAILED ||
                    it == PlatformEventCatalogue.TENANT_WIDE_ARCHIVE_VOLUME_MEASURED
            }.mapNotNull { event ->
                event.topic?.let {
                    CatalogueEntry(event.eventType, it, setOf(CatalogueEntry.EVERY_SERVICE), contentTopic = false)
                }
            }

    val entries: List<CatalogueEntry> =
        topicByType.map { (type, topic) ->
            CatalogueEntry(type, topic, setOf(SERVICE), contentTopic = false, retainedWhileResolvable = false)
        }

    /**
     * The two record-topic types evidence-svc consumes (INV-REQ-3650 b1, platform-contracts `event-log/topics.yaml`):
     * evidence-svc is not their producer, so the emission guard refuses it to emit them. `recordTopic` makes the inbox
     * consumer skip the `docsequence` check (INV-Q-3809).
     */
    val recordEntries: List<CatalogueEntry> =
        listOf(
            CatalogueEntry(
                "com.dynokore.gst.canonical.DecisionTraceRecorded.v1",
                RecordConsumerConfiguration.DECISION_TRACE_TOPIC,
                setOf("canonical-svc"),
                contentTopic = true,
                recordTopic = true,
                retainedWhileResolvable = false,
            ),
            CatalogueEntry(
                "com.dynokore.gst.platform.AuditEntryRecorded.v1",
                RecordConsumerConfiguration.AUDIT_ENTRY_TOPIC,
                setOf(CatalogueEntry.EVERY_SERVICE),
                contentTopic = false,
                recordTopic = true,
                retainedWhileResolvable = false,
            ),
        )

    /**
     * `GstReturnRecorded` (INV-Q-7251 A (1), platform-contracts `event-log/topics.yaml`): an ordinary topic of
     * reconciliation-svc that evidence-svc subscribes to, so the consumer keeps the `docsequence` check.
     */
    val returnEntries: List<CatalogueEntry> =
        listOf(
            CatalogueEntry(
                "com.dynokore.gst.reconciliation.GstReturnRecorded.v1",
                ReturnConsumerConfiguration.GST_RETURN_RECORDED_TOPIC,
                setOf("reconciliation-svc"),
                contentTopic = false,
                recordTopic = false,
                retainedWhileResolvable = false,
            ),
        )

    /**
     * `SubmissionAcknowledged` (INV-REQ-691, platform-contracts `event-log/topics.yaml`): an ordinary topic of
     * submission-svc that evidence-svc subscribes to, so the consumer keeps the `docsequence` check.
     */
    val acknowledgementEntries: List<CatalogueEntry> =
        listOf(
            CatalogueEntry(
                AcknowledgementConsumerConfiguration.EVENT_TYPE,
                AcknowledgementConsumerConfiguration.TOPIC,
                setOf(AcknowledgementConsumerConfiguration.PRODUCER),
                contentTopic = false,
                recordTopic = false,
                retainedWhileResolvable = false,
            ),
        )

    /**
     * `PayloadReady` (content topic) and `SubmissionDespatched` (INV-REQ-645, platform-contracts
     * `event-log/topics.yaml`): ordinary topics of payload-svc and submission-svc that evidence-svc subscribes to, so
     * the consumers keep the `docsequence` check.
     */
    val sealingEntries: List<CatalogueEntry> =
        listOf(
            CatalogueEntry(
                SealingConsumerConfiguration.PAYLOAD_READY_TYPE,
                SealingConsumerConfiguration.PAYLOAD_READY_TOPIC,
                setOf(SealingConsumerConfiguration.PAYLOAD_READY_PRODUCER),
                contentTopic = true,
                recordTopic = false,
                retainedWhileResolvable = false,
            ),
            CatalogueEntry(
                SealingConsumerConfiguration.DESPATCHED_TYPE,
                SealingConsumerConfiguration.DESPATCHED_TOPIC,
                setOf(SealingConsumerConfiguration.DESPATCHED_PRODUCER),
                contentTopic = false,
                recordTopic = false,
                retainedWhileResolvable = false,
            ),
        )

    /**
     * `PolicyChanged` (platform-contracts `event-log/topics.yaml`): every service produces it, none subscribes. The
     * `setPolicy` of service-runtime emits it on a stored change, so the emission guard needs the entry.
     * `TenantWideEvidenceIntegrityFailed` (INV-Q-12994 A): the platform event of a failed tenant-wide chain, produced
     * by evidence-svc alone, since a domain event cannot carry the tenant-wide entity.
     * `TenantWideArchiveVolumeMeasured` (INV-Q-14069 A): the stored audit bytes of the tenant-wide entity, likewise
     * produced by evidence-svc alone.
     */
    val platformEntries: List<CatalogueEntry> =
        jobEntries +
            CatalogueEntry(
                "com.dynokore.gst.platform.PolicyChanged.v1",
                "gst.platform.policy-changed.v1",
                setOf(CatalogueEntry.EVERY_SERVICE),
                contentTopic = false,
                recordTopic = false,
                retainedWhileResolvable = false,
            ) +
            CatalogueEntry(
                "com.dynokore.gst.platform.TenantWideEvidenceIntegrityFailed.v1",
                "gst.platform.tenant-wide-evidence-integrity-failed.v1",
                setOf(SERVICE),
                contentTopic = false,
                recordTopic = false,
                retainedWhileResolvable = false,
            ) +
            CatalogueEntry(
                "com.dynokore.gst.platform.TenantWideArchiveVolumeMeasured.v1",
                "gst.platform.tenant-wide-archive-volume-measured.v1",
                setOf(SERVICE),
                contentTopic = false,
                recordTopic = false,
                retainedWhileResolvable = false,
            )
}
