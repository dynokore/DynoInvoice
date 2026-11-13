package com.dynokore.gst.submission.bootstrap

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The events submission-svc may emit (INV-REQ-815 behaviour 2): the six of model/architecture/events.yaml it produces,
 * each on the topic its AsyncAPI channel names. The emission guard of the outbox refuses any other type. The catalogue
 * also lists the one event it consumes, produced by payload-svc, so the consumer can read its topic.
 */
@Configuration(proxyBeanMethods = false)
class EventConfiguration {
    @Bean
    fun eventCatalogue(): EventCatalogue =
        EventCatalogue(
            SubmissionEvents.entries + SubmissionEvents.consumed + SubmissionEvents.platformEntries +
                SubmissionEvents.breakGlassEntry,
        )
}

object SubmissionEvents {
    private const val SERVICE = "submission-svc"
    private const val TYPE_PREFIX = "com.dynokore.gst.submission."

    /** Event type (the AsyncAPI message name) to topic (the channel address). */
    val topicByType: Map<String, String> =
        listOf(
            "SubmissionDespatched" to "gst.submission.submission-despatched.v1",
            "SubmissionAcknowledged" to "gst.submission.submission-acknowledged.v1",
            "SubmissionFailed" to "gst.submission.submission-failed.v1",
            "ReadinessChanged" to "gst.submission.readiness-changed.v1",
            "ComplianceAlertValuesMeasured" to "gst.submission.compliance-alert-values-measured.v1",
            "DocumentCodeRaised" to "gst.submission.document-code-raised.v1",
        ).associate { (name, topic) -> "$TYPE_PREFIX$name.v1" to topic }

    val entries: List<CatalogueEntry> =
        topicByType.map { (type, topic) ->
            CatalogueEntry(type, topic, setOf(SERVICE), contentTopic = false, retainedWhileResolvable = false)
        }

    /**
     * The two platform events the job runtime of service-runtime writes through the outbox (INV-Q-16766 A): the
     * emission guard refuses any type the catalogue lacks. Another platform event is catalogued by the task that
     * emits it.
     */
    val platformEntries: List<CatalogueEntry> =
        listOf(PlatformEventCatalogue.JOB_RUN_RECORDED, PlatformEventCatalogue.JOB_REGISTERED).map { event ->
            CatalogueEntry(
                event.eventType,
                checkNotNull(event.topic),
                setOf(CatalogueEntry.EVERY_SERVICE),
                contentTopic = false,
                recordTopic = false,
                retainedWhileResolvable = false,
            )
        }

    const val PAYLOAD_READY_TOPIC = "gst.payload.payload-ready.v1"

    const val PERIOD_EXTENDED_TOPIC = "gst.tenancy.period-extended.v1"
    const val PERIOD_EXTENDED_TYPE = "com.dynokore.gst.tenancy.PeriodExtended.v1"

    const val CANONICAL_VERSION_CREATED_TOPIC = "gst.canonical.canonical-version-created.v1"
    const val CANONICAL_VERSION_CREATED_TYPE = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1"

    const val PERIOD_DUE_DATE_CHANGED_TOPIC = "gst.tenancy.period-due-date-changed.v1"
    const val PERIOD_DUE_DATE_CHANGED_TYPE = "com.dynokore.gst.tenancy.PeriodDueDateChanged.v1"

    const val PERIOD_RECORDS_CHANGED_TOPIC = "gst.tenancy.period-records-changed.v1"
    const val PERIOD_RECORDS_CHANGED_TYPE = "com.dynokore.gst.tenancy.PeriodRecordsChanged.v1"

    const val DOCUMENT_HELD_TOPIC = "gst.canonical.document-held.v1"
    const val DOCUMENT_HELD_TYPE = "com.dynokore.gst.canonical.DocumentHeld.v1"

    const val DOCUMENT_RELEASED_TOPIC = "gst.canonical.document-released.v1"
    const val DOCUMENT_RELEASED_TYPE = "com.dynokore.gst.canonical.DocumentReleased.v1"

    const val RULE_PACK_STAGED_TOPIC = "gst.rules.rule-pack-staged.v1"
    const val RULE_PACK_STAGED_TYPE = "com.dynokore.gst.rules.RulePackStaged.v1"

    const val RULE_PACK_ACTIVATED_TOPIC = "gst.rules.rule-pack-activated.v1"
    const val RULE_PACK_ACTIVATED_TYPE = "com.dynokore.gst.rules.RulePackActivated.v1"

    /**
     * `DocumentHeld` and `DocumentReleased` (producer canonical-svc, no content topic) feed the provisional register
     * entries (INV-REQ-570 behavior 2). `PayloadReady` (topics.yaml: producer payload-svc, content topic),
     * `PeriodExtended` (producer tenancy-svc, no content topic, INV-REQ-81), `CanonicalVersionCreated` (producer
     * canonical-svc, content topic) and `PeriodDueDateChanged` (producer tenancy-svc, no content topic), the last
     * two feeding the obligation register (INV-REQ-570), and `PeriodRecordsChanged` (producer tenancy-svc, no
     * content topic, INV-REQ-570); submission-svc
     * is only their subscriber.
     */
    val consumed: List<CatalogueEntry> =
        listOf(
            CatalogueEntry(
                "com.dynokore.gst.payload.PayloadReady.v1",
                PAYLOAD_READY_TOPIC,
                setOf("payload-svc"),
                contentTopic = true,
                retainedWhileResolvable = false,
            ),
            CatalogueEntry(
                PERIOD_EXTENDED_TYPE,
                PERIOD_EXTENDED_TOPIC,
                setOf("tenancy-svc"),
                contentTopic = false,
                retainedWhileResolvable = false,
            ),
            CatalogueEntry(
                CANONICAL_VERSION_CREATED_TYPE,
                CANONICAL_VERSION_CREATED_TOPIC,
                setOf("canonical-svc"),
                contentTopic = true,
                retainedWhileResolvable = false,
            ),
            CatalogueEntry(
                PERIOD_DUE_DATE_CHANGED_TYPE,
                PERIOD_DUE_DATE_CHANGED_TOPIC,
                setOf("tenancy-svc"),
                contentTopic = false,
                retainedWhileResolvable = false,
            ),
            CatalogueEntry(
                PERIOD_RECORDS_CHANGED_TYPE,
                PERIOD_RECORDS_CHANGED_TOPIC,
                setOf("tenancy-svc"),
                contentTopic = false,
                retainedWhileResolvable = false,
            ),
            CatalogueEntry(
                DOCUMENT_HELD_TYPE,
                DOCUMENT_HELD_TOPIC,
                setOf("canonical-svc"),
                contentTopic = false,
                retainedWhileResolvable = false,
            ),
            CatalogueEntry(
                DOCUMENT_RELEASED_TYPE,
                DOCUMENT_RELEASED_TOPIC,
                setOf("canonical-svc"),
                contentTopic = false,
                retainedWhileResolvable = false,
            ),
            CatalogueEntry(
                RULE_PACK_STAGED_TYPE,
                RULE_PACK_STAGED_TOPIC,
                setOf("rules-svc"),
                contentTopic = false,
                recordTopic = false,
                retainedWhileResolvable = true,
            ),
            CatalogueEntry(
                RULE_PACK_ACTIVATED_TYPE,
                RULE_PACK_ACTIVATED_TOPIC,
                setOf("rules-svc"),
                contentTopic = false,
                recordTopic = false,
                retainedWhileResolvable = false,
            ),
        )

    /** `BreakGlassSessionChanged` (INV-Q-9119 A): a platform event of every service; submission-svc only reads it. */
    val breakGlassEntry: List<CatalogueEntry> =
        listOf(
            CatalogueEntry(
                PlatformEventCatalogue.BREAK_GLASS_SESSION_CHANGED.eventType,
                checkNotNull(PlatformEventCatalogue.BREAK_GLASS_SESSION_CHANGED.topic),
                setOf(CatalogueEntry.EVERY_SERVICE),
                contentTopic = false,
                recordTopic = false,
                retainedWhileResolvable = false,
            ),
        )
}
