package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.domain.pack.DeploymentForm
import com.dynokore.gst.rules.domain.pack.GraceDays
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.gst.rules.domain.pack.ReviewPolicy
import com.dynokore.platform.rulepackkernel.fence.ReadinessOutcome
import java.time.Instant
import java.time.LocalDate

/** A scoped permission decision for arming a pack; the key is chosen by the wiring, never here (INV-Q-10027). */
fun interface ArmingPermissionPort {
    fun mayArm(userId: String): Boolean
}

/** A scoped permission decision for acknowledging a tenant's own delta; the key is chosen by the wiring. */
fun interface AcknowledgementPermissionPort {
    fun mayAcknowledge(userId: String): Boolean
}

/** The registry row of a regulatory pack version as the arming decision reads it. */
data class RegistryPackRow(
    val status: PackStatus,
    /** The staging instant: when RulePackStaged distributed the pack. */
    val registeredAt: Instant,
    val effectiveFrom: LocalDate,
)

fun interface RegistryRowReader {
    fun find(
        packType: String,
        version: String,
    ): RegistryPackRow?
}

/** One tenant that holds a COMPLETE dry-run delta for a pack version. */
data class TenantDelta(
    val tenantId: String,
    /** The `completed_at` of the tenant's latest COMPLETE row. */
    val completedAt: Instant,
    /** Whether the tenant acknowledged its delta. */
    val acknowledged: Boolean,
)

interface CompleteDeltaReader {
    /** Platform-bound: every tenant with a COMPLETE delta, one record each. */
    fun tenantsWithCompleteDelta(
        packType: String,
        version: String,
    ): List<TenantDelta>

    /** Tenant-bound: the `completed_at` of the calling tenant's latest COMPLETE delta, or null when none. */
    fun completedAt(
        tenantId: String,
        packType: String,
        version: String,
    ): Instant?
}

/** The inputs `GraceWindow.decide` takes besides the dates: VP-39 and VP-57 at tenant scope, VP-79 at installation. */
data class TenantReviewPolicy(
    val form: DeploymentForm,
    val policy: ReviewPolicy,
    val stored: GraceDays?,
    val maximum: GraceDays,
)

fun interface TenantReviewPolicyReader {
    fun read(tenantId: String): TenantReviewPolicy
}

/** The stored readiness answer of one instance (the latest row of `rules.pack_readiness_ack`). */
data class StoredReadiness(
    val instance: String,
    val outcome: ReadinessOutcome,
)

fun interface StoredReadinessReader {
    fun answers(
        packType: String,
        version: String,
    ): List<StoredReadiness>
}

/** The expected instance set of the fence (the kernel's `TrainFence.participants`). */
fun interface ExpectedInstances {
    fun participants(): Set<String>
}

/** The review record of one tenant for an unreviewed or reviewed activation. */
data class ActivationReview(
    val tenantId: String,
    val reviewed: Boolean,
    val decidedOn: LocalDate,
)

/** Everything the arming write stores. */
data class ArmingWrite(
    val packType: String,
    val version: String,
    val effectiveFrom: LocalDate,
    val reviews: List<ActivationReview>,
)

/** One atomic step: registry status ARMED, the review records, and `RulePackActivated` through the outbox. */
fun interface ArmingStore {
    fun arm(write: ArmingWrite)
}

/** A tenant's acknowledgement of its own delta; the acting user ID, never a role (INV-Q-10647). */
data class DeltaAcknowledgement(
    val tenantId: String,
    val packType: String,
    val version: String,
    val acknowledgedBy: String,
    val acknowledgedAt: Instant,
)

interface DeltaAcknowledgementStore {
    /** Writes once for tenant, pack type and version; a second call changes nothing. */
    fun recordOnce(acknowledgement: DeltaAcknowledgement)

    fun find(
        tenantId: String,
        packType: String,
        version: String,
    ): DeltaAcknowledgement?
}
