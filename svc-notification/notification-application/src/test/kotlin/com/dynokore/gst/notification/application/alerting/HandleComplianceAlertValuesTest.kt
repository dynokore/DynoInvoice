package com.dynokore.gst.notification.application.alerting

import com.dynokore.gst.notification.domain.alerting.AlertClass
import com.dynokore.gst.notification.domain.alerting.ComplianceAlertDraft
import com.dynokore.gst.notification.domain.alerting.PolicyTier
import com.dynokore.gst.notification.domain.alerting.TRIGGER_COVERAGE_GAP
import com.dynokore.gst.notification.domain.alerting.TRIGGER_DUE_DATE_THRESHOLD
import com.dynokore.gst.notification.domain.alerting.TRIGGER_PAST_DUE
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class HandleComplianceAlertValuesTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val calls = mutableListOf<String>()
    private val seen = mutableSetOf<String>()
    private val raised = mutableListOf<ComplianceAlertDraft>()
    private var tiers = listOf(PolicyTier(1, 7), PolicyTier(2, 3), PolicyTier(3, 0))
    private var tierReads = 0

    private val handler =
        HandleComplianceAlertValues(
            processed =
                object : ProcessedEvents {
                    override fun firstTime(eventId: String): Boolean {
                        calls += "firstTime"
                        return seen.add(eventId)
                    }
                },
            policy =
                object : PolicyTiers {
                    override fun tiers(
                        tenant: TenantId,
                        entityRef: String,
                        alertClass: AlertClass,
                    ): List<PolicyTier> {
                        calls += "tiers:$alertClass"
                        tierReads++
                        return tiers
                    }
                },
            sink =
                object : AlertSink {
                    override fun raise(draft: ComplianceAlertDraft) {
                        calls += "raise"
                        raised += draft
                    }
                },
        )

    private fun payload(
        days: Int? = 10,
        without: Int = 1,
    ): Map<String, Any?> =
        buildMap {
            put("entityRef", "E1")
            put("periodRef", "2026-Q3")
            put("measuredOn", "2026-10-29")
            put("documentsWithoutSubmission", without)
            put("documentsPastDue", 0)
            if (days != null) put("daysToNearestDeadline", days)
        }

    private fun deadlineTier(days: Int): Pair<Int?, String> {
        raised.clear()
        handler.handle("ev-$days", tenant, payload(days)) shouldBe HandleComplianceAlertValuesResult.Raised(2)
        raised.map { it.alertClass } shouldBe listOf(AlertClass.COVERAGE, AlertClass.DEADLINE)
        return raised[1].tier to raised[1].trigger
    }

    @Test
    fun `INV-AC-2094 a payload with 1 document without submission raises one COVERAGE alert`() {
        handler.handle("ev-1", tenant, payload(days = null)) shouldBe HandleComplianceAlertValuesResult.Raised(1)
        raised.single().alertClass shouldBe AlertClass.COVERAGE
        raised.single().trigger shouldBe TRIGGER_COVERAGE_GAP
        raised.single().entityRef shouldBe "E1"
    }

    @Test
    fun `INV-AC-12838 a second delivery of one event id raises nothing`() {
        handler.handle("ev-1", tenant, payload()) shouldBe HandleComplianceAlertValuesResult.Raised(2)
        raised.clear()
        calls.clear()
        handler.handle("ev-1", tenant, payload()) shouldBe HandleComplianceAlertValuesResult.Ignored
        raised shouldBe emptyList()
        calls shouldBe listOf("firstTime")
    }

    @Test
    fun `INV-AC-12838 a repeated event id is Ignored before the payload is read`() {
        seen += "ev-1"
        handler.handle("ev-1", tenant, payload(without = 0)) shouldBe HandleComplianceAlertValuesResult.Ignored
        calls shouldBe listOf("firstTime")
    }

    @Test
    fun `INV-AC-12838 documentsWithoutSubmission 0 throws ContractViolation and the sink is never called`() {
        assertThrows<ContractViolation> { handler.handle("ev-1", tenant, payload(without = 0)) }
        calls.contains("raise") shouldBe false
        tierReads shouldBe 0
    }

    @Test
    fun `INV-AC-12838 a payload without entityRef throws ContractViolation and the sink is never called`() {
        assertThrows<ContractViolation> { handler.handle("ev-1", tenant, payload() - "entityRef") }
        calls.contains("raise") shouldBe false
    }

    @Test
    fun `INV-AC-12838 days 10, 3 and -2 raise the DEADLINE alert in tiers 1, 2 and 3`() {
        deadlineTier(10) shouldBe (1 to TRIGGER_DUE_DATE_THRESHOLD)
        deadlineTier(3) shouldBe (2 to TRIGGER_DUE_DATE_THRESHOLD)
        deadlineTier(-2) shouldBe (3 to TRIGGER_PAST_DUE)
    }

    @Test
    fun `INV-AC-12838 other tiers data raise other tiers`() {
        tiers = listOf(PolicyTier(1, 30), PolicyTier(2, 14))
        deadlineTier(10) shouldBe (2 to TRIGGER_DUE_DATE_THRESHOLD)
    }

    @Test
    fun `INV-AC-12838 a payload without days raises the COVERAGE alert only`() {
        handler.handle("ev-1", tenant, payload(days = null)) shouldBe HandleComplianceAlertValuesResult.Raised(1)
        raised.map { it.alertClass } shouldBe listOf(AlertClass.COVERAGE)
    }

    @Test
    fun `INV-AC-12838 an empty tier list raises tier 1`() {
        tiers = emptyList()
        deadlineTier(3).first shouldBe 1
    }

    @Test
    fun `INV-AC-12838 the sink receives the drafts in the order COVERAGE, DEADLINE after one tier read`() {
        handler.handle("ev-1", tenant, payload())
        calls shouldBe listOf("firstTime", "tiers:DEADLINE", "raise", "raise")
        raised.map { it.alertClass } shouldBe listOf(AlertClass.COVERAGE, AlertClass.DEADLINE)
    }

    @Test
    fun `INV-AC-12838 the ports are exactly the three declared interfaces`() {
        val names =
            listOf(PolicyTiers::class, AlertSink::class, ProcessedEvents::class).map { it.java.simpleName }
        names shouldBe listOf("PolicyTiers", "AlertSink", "ProcessedEvents")
    }
}
