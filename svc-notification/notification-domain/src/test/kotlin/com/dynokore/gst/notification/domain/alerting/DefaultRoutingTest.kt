package com.dynokore.gst.notification.domain.alerting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DefaultRoutingTest {
    @Test
    fun `INV-AC-26001 default role per kind`() {
        val expected =
            mapOf(
                DefaultAlertKind.EXCEPTION_GROUP_DUE_WITHIN_3_DAYS to "tax.operator",
                DefaultAlertKind.READINESS_AT_RISK to "tax.approver",
                DefaultAlertKind.PACK_AWAITING_REVIEW to "tax.approver",
                DefaultAlertKind.ACC_001 to "tax.approver",
                DefaultAlertKind.FEED_STALL to "integration.engineer",
                DefaultAlertKind.WATERMARK_GAP to "integration.engineer",
                DefaultAlertKind.MANIFEST_FAILURE to "integration.engineer",
                DefaultAlertKind.WEBHOOK_DEAD_LETTERS to "integration.engineer",
                DefaultAlertKind.HEALTH to "deployment.operator",
                DefaultAlertKind.HEARTBEAT to "deployment.operator",
                DefaultAlertKind.BACKUP to "deployment.operator",
                DefaultAlertKind.ENGINE_WINDOW to "deployment.operator",
                DefaultAlertKind.MISSED_SCHEDULED_JOBS to "deployment.operator",
                DefaultAlertKind.ELEVATION_REQUEST to "security.officer",
                DefaultAlertKind.ACC_002 to "security.officer",
                DefaultAlertKind.ACC_003 to "security.officer",
                DefaultAlertKind.EVIDENCE_INTEGRITY_FAILURE to "security.officer",
                DefaultAlertKind.PLAN_LIMIT to "billing.contact",
            )
        expected.size shouldBe 18
        DefaultAlertKind.entries.associateWith { defaultRole(it) } shouldBe expected
        DefaultAlertKind.entries.map { defaultRole(it) }.toSet() shouldBe
            setOf(
                "tax.operator",
                "tax.approver",
                "integration.engineer",
                "deployment.operator",
                "security.officer",
                "billing.contact",
            )
    }
}
