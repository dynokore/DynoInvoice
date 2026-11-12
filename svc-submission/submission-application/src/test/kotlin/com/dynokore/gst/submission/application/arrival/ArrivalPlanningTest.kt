package com.dynokore.gst.submission.application.arrival

import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.routing.ArrivalCode
import com.dynokore.gst.submission.domain.routing.UnresolvedInput
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class ArrivalPlanningTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val entries =
        mapOf(
            "D1" to RegisteredDocument("E-001", "2026-Q1", RowKind.INVOICE),
            "D2" to RegisteredDocument("E-001", "2026-Q1", RowKind.CREDIT_NOTE),
            "D4" to RegisteredDocument("E-001", "2026-Q2", RowKind.INVOICE),
        )
    private var findCalls = 0
    private var filedCalls = 0
    private val unresolved = mutableListOf<UnresolvedInput>()
    private var unknownEntries = 0

    private val planning =
        ArrivalPlanning(
            object : RegisteredDocuments {
                override fun find(
                    tenant: TenantId,
                    docUuid: String,
                ): RegisteredDocument? {
                    findCalls++
                    return entries[docUuid]
                }
            },
            object : FiledPeriods {
                override fun isFiled(
                    entityRef: String,
                    periodRef: String,
                ): Boolean {
                    filedCalls++
                    return entityRef == "E-001" && periodRef == "2026-Q1"
                }
            },
            object : ArrivalMetrics {
                override fun unresolvedInput(input: UnresolvedInput) {
                    unresolved += input
                }

                override fun unknownRegisterEntry() {
                    unknownEntries++
                }
            },
        )

    @Test
    fun `INV-AC-21091 first version of a filed period takes the despatch-at-once lane and lists SUB-010`() {
        planning.plan(tenant, "D1", emptyList()) shouldBe
            ArrivalPlan(Lane.DEADLINE_NEAR, listOf("SUB-010"), "E-001", "2026-Q1")
        unresolved shouldBe emptyList()
        unknownEntries shouldBe 0
    }

    @Test
    fun `INV-AC-21091 first version of an unfiled period is routine with no codes`() {
        planning.plan(tenant, "D4", emptyList()) shouldBe ArrivalPlan(Lane.LIVE, emptyList(), "E-001", "2026-Q2")
        unresolved shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21091 credit note of a filed period never lists PER-007 and counts the unknown pre-obligation`() {
        planning.plan(tenant, "D2", emptyList()) shouldBe
            ArrivalPlan(Lane.DEADLINE_NEAR, listOf("SUB-010"), "E-001", "2026-Q1")
        unresolved shouldBe listOf(UnresolvedInput.PRECEDING_PRE_OBLIGATION)
    }

    @Test
    fun `INV-AC-21091 unknown register entry is routine, counted once, and asks no filing status`() {
        planning.plan(tenant, "D3", emptyList()) shouldBe ArrivalPlan(Lane.LIVE, emptyList(), null, null)
        unknownEntries shouldBe 1
        filedCalls shouldBe 0
        unresolved shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21091 version 2 is routine and calls no port and counts nothing`() {
        planning.plan(tenant, "D1", listOf(LifecycleState.READY)) shouldBe
            ArrivalPlan(Lane.LIVE, emptyList(), null, null)
        findCalls shouldBe 0
        filedCalls shouldBe 0
        unknownEntries shouldBe 0
        unresolved shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21091 the raised code set is exactly SUB-010`() {
        ArrivalPlanning.RAISED_CODES shouldBe setOf(ArrivalCode.SUB_010)
    }
}
