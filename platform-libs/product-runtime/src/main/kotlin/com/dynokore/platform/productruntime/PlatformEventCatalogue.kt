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
    val AUTHORITY_CHANGED = fixed("AuthorityChanged", "authority-changed")
    val INSTALLATION_STATUS_CHANGED = fixed("InstallationStatusChanged", "installation-status-changed")
    val OPERATOR_SESSION_KEYS_CHANGED = fixed("OperatorSessionKeysChanged", "operator-session-keys-changed")
    val TENANT_WIDE_EVIDENCE_INTEGRITY_FAILED =
        fixed("TenantWideEvidenceIntegrityFailed", "tenant-wide-evidence-integrity-failed")
    val BREAK_GLASS_SESSION_CHANGED = fixed("BreakGlassSessionChanged", "break-glass-session-changed")
    val BREAK_GLASS_ACTION_REPORTED = fixed("BreakGlassActionReported", "break-glass-action-reported")
    val TENANT_WIDE_ARCHIVE_VOLUME_MEASURED =
        fixed("TenantWideArchiveVolumeMeasured", "tenant-wide-archive-volume-measured")

    val AUTHORITY_HEAD_OBSERVED = fixed("AuthorityHeadObserved", "authority-head-observed")
    val CERTIFICATION_MARK_REQUESTED = fixed("CertificationMarkRequested", "certification-mark-requested")
    val RULE_PACK_STAGED = fixed("RulePackStaged", "rule-pack-staged")
    val PACK_CURRENCY_CHANGED = fixed("PackCurrencyChanged", "pack-currency-changed")
    val CONNECTION_CREDENTIAL_EXPIRY_REMINDER_REQUESTED =
        fixed("ConnectionCredentialExpiryReminderRequested", "connection-credential-expiry-reminder-requested")

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
            AUTHORITY_CHANGED,
            INSTALLATION_STATUS_CHANGED,
            OPERATOR_SESSION_KEYS_CHANGED,
            TENANT_WIDE_EVIDENCE_INTEGRITY_FAILED,
            BREAK_GLASS_SESSION_CHANGED,
            BREAK_GLASS_ACTION_REPORTED,
            TENANT_WIDE_ARCHIVE_VOLUME_MEASURED,
            AUTHORITY_HEAD_OBSERVED,
            CERTIFICATION_MARK_REQUESTED,
            RULE_PACK_STAGED,
            PACK_CURRENCY_CHANGED,
            CONNECTION_CREDENTIAL_EXPIRY_REMINDER_REQUESTED,
        )

    /** True when [eventType] is a platform event. */
    fun isPlatform(eventType: String): Boolean = ALL.any { it.eventType == eventType }
}
