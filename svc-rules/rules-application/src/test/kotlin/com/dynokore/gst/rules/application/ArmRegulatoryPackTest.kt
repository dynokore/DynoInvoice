package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.application.ArmRegulatoryPackResult.Aborted
import com.dynokore.gst.rules.application.ArmRegulatoryPackResult.AcknowledgementsPending
import com.dynokore.gst.rules.application.ArmRegulatoryPackResult.Armed
import com.dynokore.gst.rules.application.ArmRegulatoryPackResult.Denied
import com.dynokore.gst.rules.application.ArmRegulatoryPackResult.EffectiveDateMismatch
import com.dynokore.gst.rules.application.ArmRegulatoryPackResult.NotArmable
import com.dynokore.gst.rules.application.ArmRegulatoryPackResult.NotFound
import com.dynokore.gst.rules.application.ArmRegulatoryPackResult.WindowOpen
import com.dynokore.gst.rules.domain.pack.DeploymentForm
import com.dynokore.gst.rules.domain.pack.GraceDays
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.gst.rules.domain.pack.ReviewPolicy
import com.dynokore.platform.rulepackkernel.fence.ReadinessFailure
import com.dynokore.platform.rulepackkernel.fence.ReadinessOutcome
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ArmRegulatoryPackTest {
    private class MovableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    /** What every double holds; read before and after a call and compared by equality. */
    private data class Tables(
        val status: PackStatus,
        val armWrites: List<ArmingWrite>,
        val reads: List<String>,
    )

    private class Env {
        val today: LocalDate = LocalDate.of(2026, 12, 1)
        val clock = MovableClock(Instant.parse("2026-12-01T10:00:00Z"))
        var status = PackStatus.APPROVED
        var effectiveFrom: LocalDate = today
        var registeredAt: Instant = Instant.parse("2026-11-01T00:00:00Z")
        var allowed = true
        var row: Boolean = true
        val tenants = mutableListOf<TenantDelta>()
        val policy = mutableMapOf<String, TenantReviewPolicy>()
        val answers = mutableListOf<StoredReadiness>()
        val armWrites = mutableListOf<ArmingWrite>()
        val reads = mutableListOf<String>()

        fun review(
            policy: ReviewPolicy = ReviewPolicy.REVIEW_IN_GRACE_WINDOW,
            form: DeploymentForm = DeploymentForm.SAAS,
        ) = TenantReviewPolicy(form, policy, GraceDays(7), GraceDays(14))

        fun tenant(
            id: String,
            completed: String,
            acknowledged: Boolean,
            policy: TenantReviewPolicy = review(),
        ) {
            tenants += TenantDelta(id, Instant.parse(completed), acknowledged)
            this.policy[id] = policy
        }

        fun loaded(vararg instances: String) =
            instances.forEach {
                answers +=
                    StoredReadiness(it, ReadinessOutcome.Loaded)
            }

        val useCase =
            ArmRegulatoryPack(
                permission = { allowed.also { reads += "permission" } },
                registry =
                    RegistryRowReader { _, _ ->
                        reads += "registry"
                        if (row) RegistryPackRow(status, registeredAt, effectiveFrom) else null
                    },
                deltas =
                    object : CompleteDeltaReader {
                        override fun tenantsWithCompleteDelta(
                            packType: String,
                            version: String,
                        ): List<TenantDelta> = tenants.also { reads += "deltas" }

                        override fun completedAt(
                            tenantId: String,
                            packType: String,
                            version: String,
                        ): Instant? = null
                    },
                policies = { policy.getValue(it).also { _ -> reads += "policy" } },
                readiness = StoredReadinessReader { _, _ -> answers.toList().also { reads += "readiness" } },
                expected = { setOf("rules-svc", "payload-svc") },
                store =
                    ArmingStore {
                        armWrites += it
                        status = PackStatus.ARMED
                    },
                clock = clock,
                window = Duration.parse("PT15M"),
            )

        fun tables() = Tables(status, armWrites.toList(), emptyList())

        fun arm(on: LocalDate = effectiveFrom) = useCase.execute("PINT-SG", "1.1", on, "u-op")
    }

    private fun Env.threeTenants() {
        tenant("t1", "2026-11-01T08:00:00Z", acknowledged = true)
        tenant("t2", "2026-11-01T08:00:00Z", acknowledged = false)
        tenant("t3", "2026-11-01T08:00:00Z", acknowledged = false, policy = review(ReviewPolicy.NOTICE_ONLY))
        loaded("rules-svc", "payload-svc")
    }

    private fun Env.refused(
        expected: ArmRegulatoryPackResult,
        on: LocalDate = effectiveFrom,
    ) {
        val before = tables()
        arm(on) shouldBe expected
        tables() shouldBe before
    }

    @Test
    fun `INV-AC-22939 case a three tenants give review records true false false and one arming write`() {
        val e = Env().apply { threeTenants() }
        arm(e) shouldBe Armed
        e.armWrites.single().reviews shouldBe
            listOf(
                ActivationReview("t1", true, e.today),
                ActivationReview("t2", false, e.today),
                ActivationReview("t3", false, e.today),
            )
        e.status shouldBe PackStatus.ARMED
    }

    private fun arm(e: Env) = e.arm()

    @Test
    fun `INV-AC-22939 case a without the acknowledgement row the first tenant is unreviewed`() {
        val e = Env().apply { threeTenants() }
        e.tenants[0] = e.tenants[0].copy(acknowledged = false)
        arm(e) shouldBe Armed
        e.armWrites
            .single()
            .reviews
            .map { it.reviewed } shouldBe listOf(false, false, false)
    }

    @Test
    fun `INV-AC-22939 case b a tenant whose window is still open refuses and every table is equal`() {
        val e = Env().apply { loaded("rules-svc", "payload-svc") }
        e.effectiveFrom = LocalDate.of(2026, 12, 15)
        e.tenant("t1", "2026-11-30T08:00:00Z", acknowledged = false)
        e.refused(WindowOpen("t1", LocalDate.of(2026, 12, 7)))
    }

    @Test
    fun `INV-AC-22939 case c the private form records no review row`() {
        val e = Env().apply { loaded("rules-svc", "payload-svc") }
        e.tenant("t1", "2026-11-30T08:00:00Z", acknowledged = false, policy = e.review(form = DeploymentForm.PRIVATE))
        arm(e) shouldBe Armed
        e.armWrites.single().reviews shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22939 case d a request date one day later or earlier is refused and the equal date arms`() {
        val e = Env().apply { threeTenants() }
        e.refused(EffectiveDateMismatch(e.today.plusDays(1), e.today), e.today.plusDays(1))
        e.refused(EffectiveDateMismatch(e.today.minusDays(1), e.today), e.today.minusDays(1))
        e.arm(e.today) shouldBe Armed
    }

    @Test
    fun `INV-AC-22939 case e one instance unacknowledged after the window aborts and the status stays APPROVED`() {
        val e = Env().apply { threeTenants() }
        e.answers.removeLast()
        e.refused(Aborted("PINT-SG", "1.1", emptySet(), setOf("payload-svc"), false))
        e.status shouldBe PackStatus.APPROVED
    }

    @Test
    fun `INV-AC-22939 case e an instance that answered Failed aborts and nothing is written`() {
        val e = Env().apply { threeTenants() }
        e.answers.removeLast()
        e.answers += StoredReadiness("payload-svc", ReadinessOutcome.Failed(ReadinessFailure.COMPILE_FAILED, "x"))
        e.refused(Aborted("PINT-SG", "1.1", setOf("payload-svc"), emptySet(), false))
    }

    @Test
    fun `INV-AC-22939 case f acknowledgements missing inside the window are pending and every table is equal`() {
        val e = Env().apply { threeTenants() }
        e.registeredAt = e.clock.now.minusSeconds(300)
        e.answers.removeLast()
        e.refused(AcknowledgementsPending(setOf("payload-svc")))
    }

    @Test
    fun `INV-AC-22939 case g an acknowledgement stored after the window but before the request counts`() {
        val e = Env().apply { threeTenants() }
        e.answers.removeLast()
        e.refused(Aborted("PINT-SG", "1.1", emptySet(), setOf("payload-svc"), false))
        e.loaded("payload-svc")
        arm(e) shouldBe Armed
        e.armWrites.size shouldBe 1
    }

    @Test
    fun `INV-AC-22939 denied not found and not armable refuse and a denied caller reads no other port`() {
        val e = Env().apply { threeTenants() }
        e.allowed = false
        e.arm() shouldBe Denied
        e.reads shouldBe listOf("permission")
        e.allowed = true
        e.status = PackStatus.STAGED
        e.refused(NotArmable(PackStatus.STAGED))
        e.status = PackStatus.APPROVED
        e.row = false
        e.refused(NotFound)
    }

    @Test
    fun `INV-AC-22939 a pack ACKNOWLEDGED is armable and a frozen status is not`() {
        val e = Env().apply { threeTenants() }
        e.status = PackStatus.ARMED
        e.refused(NotArmable(PackStatus.ARMED))
        e.status = PackStatus.ACKNOWLEDGED
        arm(e) shouldBe Armed
    }

    @Test
    fun `INV-AC-22939 a failing arming write aborts with armingFailed and writes nothing`() {
        val e = Env().apply { threeTenants() }
        val failing =
            ArmRegulatoryPack(
                { true },
                { _, _ -> RegistryPackRow(PackStatus.APPROVED, e.registeredAt, e.today) },
                object : CompleteDeltaReader {
                    override fun tenantsWithCompleteDelta(
                        packType: String,
                        version: String,
                    ) = emptyList<TenantDelta>()

                    override fun completedAt(
                        tenantId: String,
                        packType: String,
                        version: String,
                    ): Instant? = null
                },
                { e.review() },
                { _, _ -> e.answers },
                { setOf("rules-svc", "payload-svc") },
                { error("db down") },
                e.clock,
                Duration.parse("PT15M"),
            )
        failing.execute("PINT-SG", "1.1", e.today, "u-op") shouldBe
            Aborted("PINT-SG", "1.1", emptySet(), emptySet(), true)
    }
}
