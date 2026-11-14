package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.domain.pack.GraceDecision
import com.dynokore.gst.rules.domain.pack.GraceWindow
import com.dynokore.gst.rules.domain.pack.NoGraceWindow
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.rulepackkernel.fence.ActivationFence
import com.dynokore.platform.rulepackkernel.fence.FenceOutcome
import com.dynokore.platform.rulepackkernel.fence.PackReadinessAcknowledgement
import com.dynokore.platform.rulepackkernel.fence.ReadinessOutcome
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/** The answer of arming a regulatory pack; every refusal is typed and leaves the ports unwritten (INV-AC-22939). */
sealed interface ArmRegulatoryPackResult {
    data object Armed : ArmRegulatoryPackResult

    data object Denied : ArmRegulatoryPackResult

    data object NotFound : ArmRegulatoryPackResult

    /** Neither APPROVED nor ACKNOWLEDGED. */
    data class NotArmable(
        val status: PackStatus,
    ) : ArmRegulatoryPackResult

    /** The request date differs from the staged `effective_from`, earlier or later. */
    data class EffectiveDateMismatch(
        val requested: LocalDate,
        val staged: LocalDate,
    ) : ArmRegulatoryPackResult

    /** A tenant's grace window is still open (SaaS form). */
    data class WindowOpen(
        val tenantId: String,
        val endsOn: LocalDate,
    ) : ArmRegulatoryPackResult

    /** Acknowledgements are missing and the fence window is open. */
    data class AcknowledgementsPending(
        val missing: Set<String>,
    ) : ArmRegulatoryPackResult

    /** The fence abandoned the activation (RUL-005); the names are for the log line of the caller. */
    data class Aborted(
        val packType: String,
        val version: String,
        val failed: Set<String>,
        val missing: Set<String>,
        val armingFailed: Boolean,
    ) : ArmRegulatoryPackResult
}

/** Arms an APPROVED or ACKNOWLEDGED regulatory pack behind the two-phase fence (INV-REQ-1166, 858, 952). */
@Suppress("LongParameterList") // constructor injection of the ports (CS-05)
class ArmRegulatoryPack(
    private val permission: ArmingPermissionPort,
    private val registry: RegistryRowReader,
    private val deltas: CompleteDeltaReader,
    private val policies: TenantReviewPolicyReader,
    private val readiness: StoredReadinessReader,
    private val expected: ExpectedInstances,
    private val store: ArmingStore,
    private val clock: Clock,
    private val window: Duration,
) {
    fun execute(
        packType: String,
        version: String,
        effectiveFrom: LocalDate,
        actor: String,
    ): ArmRegulatoryPackResult {
        val allowed = permission.mayArm(actor)
        val row = if (allowed) registry.find(packType, version) else null
        return when {
            !allowed -> ArmRegulatoryPackResult.Denied
            row == null -> ArmRegulatoryPackResult.NotFound
            else -> precondition(row, effectiveFrom) ?: decide(packType, version, row)
        }
    }

    private fun precondition(
        row: RegistryPackRow,
        requested: LocalDate,
    ): ArmRegulatoryPackResult? =
        when {
            row.status != PackStatus.APPROVED && row.status != PackStatus.ACKNOWLEDGED -> {
                ArmRegulatoryPackResult.NotArmable(row.status)
            }

            requested != row.effectiveFrom -> {
                ArmRegulatoryPackResult.EffectiveDateMismatch(requested, row.effectiveFrom)
            }

            else -> {
                null
            }
        }

    private fun decide(
        packType: String,
        version: String,
        row: RegistryPackRow,
    ): ArmRegulatoryPackResult {
        val today = LocalDate.now(clock)
        val reviews = mutableListOf<ActivationReview>()
        for (delta in deltas.tenantsWithCompleteDelta(packType, version)) {
            when (val d = decision(delta, row, today)) {
                is GraceDecision.Open -> return ArmRegulatoryPackResult.WindowOpen(delta.tenantId, d.endsOn)
                GraceDecision.ActivateReviewed -> reviews += ActivationReview(delta.tenantId, true, today)
                GraceDecision.ActivateUnreviewed -> reviews += ActivationReview(delta.tenantId, false, today)
                NoGraceWindow -> Unit
            }
        }
        return runFence(packType, version, ArmingWrite(packType, version, row.effectiveFrom, reviews), row)
    }

    private fun decision(
        delta: TenantDelta,
        row: RegistryPackRow,
        today: LocalDate,
    ): GraceDecision {
        val p = policies.read(delta.tenantId)
        return GraceWindow.decide(
            form = p.form,
            policy = p.policy,
            deliveredOn = delta.completedAt.atZone(clock.zone).toLocalDate(),
            effectiveFrom = row.effectiveFrom,
            stored = p.stored,
            maximum = p.maximum,
            reviewed = delta.acknowledged,
            today = today,
        )
    }

    private fun runFence(
        packType: String,
        version: String,
        write: ArmingWrite,
        row: RegistryPackRow,
    ): ArmRegulatoryPackResult {
        val id = PackId(packType)
        val label = PackVersionLabel(version)
        val fence =
            ActivationFence(
                id,
                label,
                expected.participants(),
                window,
                StagedStartClock(row.registeredAt, clock),
            ) { _, _ ->
                store.arm(write)
            }
        readiness.answers(packType, version).forEach {
            fence.record(PackReadinessAcknowledgement(id, label, it.instance, it.outcome))
        }
        return when (val outcome = fence.evaluate()) {
            FenceOutcome.Armed -> {
                ArmRegulatoryPackResult.Armed
            }

            is FenceOutcome.Pending -> {
                ArmRegulatoryPackResult.AcknowledgementsPending(outcome.missing)
            }

            is FenceOutcome.Aborted -> {
                ArmRegulatoryPackResult.Aborted(
                    packType,
                    version,
                    outcome.failed.keys,
                    outcome.missing,
                    outcome.armingFailed,
                )
            }
        }
    }

    /** The first reading is the staging instant, every later reading is the real clock. */
    private class StagedStartClock(
        private val start: Instant,
        private val real: Clock,
    ) : Clock() {
        private val first = AtomicBoolean(true)

        override fun getZone(): ZoneId = real.zone

        override fun withZone(zone: ZoneId): Clock = StagedStartClock(start, real.withZone(zone))

        override fun instant(): Instant = if (first.getAndSet(false)) start else real.instant()
    }
}
