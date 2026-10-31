package com.dynokore.platform.serviceruntime.events

/**
 * The platform event types whose schemas `service-runtime` owns (INV-REQ-1226 b1-b2, STK-61), as they appear in
 * the envelope's `type` (API-40): name and major version only. A minor version is additive within the major
 * (INV-REQ-1163, API-41), so it never shows in the type: a reader accepts every minor of the major it knows.
 */
object PlatformEventTypes {
    private const val PREFIX = "com.dynokore.gst.platform."

    const val AUDIT_ENTRY_RECORDED = "${PREFIX}AuditEntryRecorded.v1"
    const val JOB_RUN_RECORDED = "${PREFIX}JobRunRecorded.v1"
    const val JOB_REGISTERED = "${PREFIX}JobRegistered.v1"
    const val CONNECTION_STATUS_CHANGED = "${PREFIX}ConnectionStatusChanged.v1"
    const val EVIDENCE_ARTEFACT_RECORDED = "${PREFIX}EvidenceArtefactRecorded.v1"
    const val POLICY_CHANGED = "${PREFIX}PolicyChanged.v1"
    const val PACK_READINESS_ACKNOWLEDGED = "${PREFIX}PackReadinessAcknowledged.v1"
    const val BREAK_GLASS_SESSION_CHANGED = "${PREFIX}BreakGlassSessionChanged.v1"

    /** The topics of the three events INV-T-2875 adds readers or a publisher for (INV-API-3306, 3308, 3310). */
    const val POLICY_CHANGED_TOPIC = "gst.platform.policy-changed.v1"
    const val CONNECTION_STATUS_CHANGED_TOPIC = "gst.platform.connection-status-changed.v1"
    const val EVIDENCE_ARTEFACT_RECORDED_TOPIC = "gst.platform.evidence-artefact-recorded.v1"
}
