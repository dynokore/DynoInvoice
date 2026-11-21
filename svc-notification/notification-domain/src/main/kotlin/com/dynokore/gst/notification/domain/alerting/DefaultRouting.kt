package com.dynokore.gst.notification.domain.alerting

const val ROLE_TAX_OPERATOR = "tax.operator"
const val ROLE_TAX_APPROVER = "tax.approver"
const val ROLE_INTEGRATION_ENGINEER = "integration.engineer"
const val ROLE_SECURITY_OFFICER = "security.officer"
const val ROLE_BILLING_CONTACT = "billing.contact"

/** Fallback role when a default role has no holder in the tenant (INV-REQ-910 handling). */
const val ROLE_TENANT_ADMIN = "tenant.admin"

/** The 18 alert kinds that INV-REQ-910 gives a default recipient role. */
enum class DefaultAlertKind {
    EXCEPTION_GROUP_DUE_WITHIN_3_DAYS,
    READINESS_AT_RISK,
    PACK_AWAITING_REVIEW,
    ACC_001,
    FEED_STALL,
    WATERMARK_GAP,
    MANIFEST_FAILURE,
    WEBHOOK_DEAD_LETTERS,
    HEALTH,
    HEARTBEAT,
    BACKUP,
    ENGINE_WINDOW,
    MISSED_SCHEDULED_JOBS,
    ELEVATION_REQUEST,
    ACC_002,
    ACC_003,
    EVIDENCE_INTEGRITY_FAILURE,
    PLAN_LIMIT,
}

/** INV-REQ-910 behavior 1: the default role of [kind]. Exhaustive, so a kind without a role cannot compile. */
fun defaultRole(kind: DefaultAlertKind): String =
    when (kind) {
        DefaultAlertKind.EXCEPTION_GROUP_DUE_WITHIN_3_DAYS -> ROLE_TAX_OPERATOR

        DefaultAlertKind.READINESS_AT_RISK,
        DefaultAlertKind.PACK_AWAITING_REVIEW,
        DefaultAlertKind.ACC_001,
        -> ROLE_TAX_APPROVER

        DefaultAlertKind.FEED_STALL,
        DefaultAlertKind.WATERMARK_GAP,
        DefaultAlertKind.MANIFEST_FAILURE,
        DefaultAlertKind.WEBHOOK_DEAD_LETTERS,
        -> ROLE_INTEGRATION_ENGINEER

        DefaultAlertKind.HEALTH,
        DefaultAlertKind.HEARTBEAT,
        DefaultAlertKind.BACKUP,
        DefaultAlertKind.ENGINE_WINDOW,
        DefaultAlertKind.MISSED_SCHEDULED_JOBS,
        -> ROLE_DEPLOYMENT_OPERATOR

        DefaultAlertKind.ELEVATION_REQUEST,
        DefaultAlertKind.ACC_002,
        DefaultAlertKind.ACC_003,
        DefaultAlertKind.EVIDENCE_INTEGRITY_FAILURE,
        -> ROLE_SECURITY_OFFICER

        DefaultAlertKind.PLAN_LIMIT -> ROLE_BILLING_CONTACT
    }
