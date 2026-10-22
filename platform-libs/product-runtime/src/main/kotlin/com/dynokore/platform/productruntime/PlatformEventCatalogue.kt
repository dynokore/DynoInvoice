package com.dynokore.platform.productruntime

/**
 * One platform event (INV-REQ-1226 b1): its name, its CloudEvents type and its fixed topic. The two replay protocol
 * messages have no fixed topic: they travel on the per-pair replay and per-consumer replay-request topics
 * (INV-REQ-1205 b4), so [topic] is null for them.
 */
data class PlatformEvent(
    val name: String,
    val eventType: String,
    val topic: String?,
)

/**
 * The platform-event catalogue (FR-AH.15, INV-REQ-1226 b1; INV-MOD-2745): the platform class, whose schemas
 * service-runtime owns, which sits outside the domain dependency rules, and which every service may emit.
 */
object PlatformEventCatalogue {
    private const val TYPE_PREFIX = "com.dynokore.gst.platform."
    private const val TOPIC_PREFIX = "gst.platform."

    private fun fixed(
        name: String,
        topic: String,
    ) = PlatformEvent(name, "$TYPE_PREFIX$name.v1", "$TOPIC_PREFIX$topic.v1")

    private fun replay(name: String) = PlatformEvent(name, "$TYPE_PREFIX$name.v1", null)

    val AUDIT_ENTRY_RECORDED = fixed("AuditEntryRecorded", "audit-entry-recorded")
    val JOB_RUN_RECORDED = fixed("JobRunRecorded", "job-run-recorded")
    val JOB_REGISTERED = fixed("JobRegistered", "job-registered")
    val CONNECTION_STATUS_CHANGED = fixed("ConnectionStatusChanged", "connection-status-changed")
    val EVIDENCE_ARTEFACT_RECORDED = fixed("EvidenceArtefactRecorded", "evidence-artefact-recorded")
    val POLICY_CHANGED = fixed("PolicyChanged", "policy-changed")
    val PACK_READINESS_ACKNOWLEDGED = fixed("PackReadinessAcknowledged", "pack-readiness-acknowledged")
    val REPLAY_REQUESTED = replay("ReplayRequested")
    val REPLAY_COMPLETED = replay("ReplayCompleted")
    val PRIVACY_NOTICE_DUE = fixed("PrivacyNoticeDue", "privacy-notice-due")
    val ACCREDITATION_ALERT_RAISED = fixed("AccreditationAlertRaised", "accreditation-alert-raised")
    val BREAK_GLASS_ALERT_RAISED = fixed("BreakGlassAlertRaised", "break-glass-alert-raised")

    /** Every platform event, in INV-REQ-1226 b1's order. */
    val ALL: List<PlatformEvent> =
        listOf(
            AUDIT_ENTRY_RECORDED,
            JOB_RUN_RECORDED,
            JOB_REGISTERED,
            CONNECTION_STATUS_CHANGED,
            EVIDENCE_ARTEFACT_RECORDED,
            POLICY_CHANGED,
            PACK_READINESS_ACKNOWLEDGED,
            REPLAY_REQUESTED,
            REPLAY_COMPLETED,
            PRIVACY_NOTICE_DUE,
            ACCREDITATION_ALERT_RAISED,
            BREAK_GLASS_ALERT_RAISED,
        )

    /** True when [eventType] is a platform event. */
    fun isPlatform(eventType: String): Boolean = ALL.any { it.eventType == eventType }
}
