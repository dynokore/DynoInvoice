package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.application.AcknowledgePackDeltaResult.Acknowledged
import com.dynokore.gst.rules.application.AcknowledgePackDeltaResult.Denied
import com.dynokore.gst.rules.application.AcknowledgePackDeltaResult.NoCompleteDelta
import com.dynokore.gst.rules.application.AcknowledgePackDeltaResult.NotFound
import com.dynokore.gst.rules.application.AcknowledgePackDeltaResult.NotOpen
import com.dynokore.gst.rules.application.AcknowledgePackDeltaResult.WindowClosed
import com.dynokore.gst.rules.domain.pack.DeploymentForm
import com.dynokore.gst.rules.domain.pack.GraceDays
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.gst.rules.domain.pack.ReviewPolicy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class AcknowledgePackDeltaTest {
    private class Env {
        val clock: Clock = Clock.fixed(Instant.parse("2026-12-01T10:00:00Z"), ZoneOffset.UTC)
        var allowed = true
        var status: PackStatus? = PackStatus.STAGED
        var effectiveFrom: LocalDate = LocalDate.of(2026, 12, 20)
        var completedAt: Instant? = Instant.parse("2026-11-30T08:00:00Z")
        var form = DeploymentForm.SAAS
        val acks = mutableMapOf<Triple<String, String, String>, DeltaAcknowledgement>()
        val writes = mutableListOf<DeltaAcknowledgement>()

        val useCase =
            AcknowledgePackDelta(
                { allowed },
                { _, _ -> status?.let { RegistryPackRow(it, Instant.parse("2026-11-01T00:00:00Z"), effectiveFrom) } },
                object : CompleteDeltaReader {
                    override fun tenantsWithCompleteDelta(
                        packType: String,
                        version: String,
                    ) = emptyList<TenantDelta>()

                    override fun completedAt(
                        tenantId: String,
                        packType: String,
                        version: String,
                    ) = completedAt
                },
                { TenantReviewPolicy(form, ReviewPolicy.REVIEW_IN_GRACE_WINDOW, GraceDays(7), GraceDays(14)) },
                object : DeltaAcknowledgementStore {
                    override fun recordOnce(acknowledgement: DeltaAcknowledgement) {
                        val key = Triple(acknowledgement.tenantId, acknowledgement.packType, acknowledgement.version)
                        if (key !in acks) {
                            acks[key] = acknowledgement
                            writes += acknowledgement
                        }
                    }

                    override fun find(
                        tenantId: String,
                        packType: String,
                        version: String,
                    ) = acks[Triple(tenantId, packType, version)]
                },
                clock,
            )

        fun call(actor: String = "u-1") = useCase.execute("t1", "PINT-SG", "1.1", actor)
    }

    private fun Env.refused(expected: AcknowledgePackDeltaResult) {
        val before = writes.toList() to status
        call() shouldBe expected
        (writes.toList() to status) shouldBe before
    }

    @Test
    fun `INV-AC-22939 case h the answer is ACKNOWLEDGED with the window end and a second call changes nothing`() {
        val e = Env()
        // delivered 2026-11-30 plus 7 days is 2026-12-07, earlier than effectiveFrom 2026-12-20
        e.call() shouldBe Acknowledged(PackStatus.ACKNOWLEDGED, LocalDate.of(2026, 12, 20), LocalDate.of(2026, 12, 7))
        e.writes.single().acknowledgedBy shouldBe "u-1"
        e.status shouldBe PackStatus.STAGED
        val after = e.writes.toList()
        e.call("u-2") shouldBe
            Acknowledged(PackStatus.ACKNOWLEDGED, LocalDate.of(2026, 12, 20), LocalDate.of(2026, 12, 7))
        e.writes shouldBe after
    }

    @Test
    fun `INV-AC-22939 case h the window end is capped at effectiveFrom`() {
        val e = Env().apply { effectiveFrom = LocalDate.of(2026, 12, 3) }
        e.call() shouldBe Acknowledged(PackStatus.ACKNOWLEDGED, LocalDate.of(2026, 12, 3), LocalDate.of(2026, 12, 3))
    }

    @Test
    fun `INV-AC-22939 case h refusals write nothing`() {
        val e = Env()
        e.allowed = false
        e.refused(Denied)
        e.allowed = true
        e.status = null
        e.refused(NotFound)
        e.status = PackStatus.ARMED
        e.refused(NotOpen(PackStatus.ARMED))
        e.status = PackStatus.APPROVED
        e.completedAt = null
        e.refused(NoCompleteDelta)
        e.completedAt = Instant.parse("2026-11-01T08:00:00Z")
        e.refused(WindowClosed(LocalDate.of(2026, 11, 8)))
    }

    @Test
    fun `INV-AC-22939 case i the private form answers NoGraceWindow and stores nothing while SaaS acknowledges`() {
        val e = Env().apply { form = DeploymentForm.PRIVATE }
        e.refused(AcknowledgePackDeltaResult.NoGraceWindow)
        e.acks shouldBe emptyMap()
        e.form = DeploymentForm.SAAS
        e.call() shouldBe Acknowledged(PackStatus.ACKNOWLEDGED, LocalDate.of(2026, 12, 20), LocalDate.of(2026, 12, 7))
    }
}
