package com.dynokore.gst.access.application.breakglass

import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.domain.breakglass.BreakGlassPairing
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.TenantAuthority
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The permission an approver of a break-glass unlock holds (INV-Q-10138 B); the pairing stays a business rule. */
val BREAK_GLASS_UNLOCK = Permission("break-glass.unlock")

/** The longest approval window, and the default (INV-Q-9109 A). */
val MAX_APPROVAL_WINDOW: Duration = Duration.ofMinutes(15)

/** One approval of an unlock by an approver other than the unlocking caller. */
data class BreakGlassApproval(
    val ref: String,
    val tenantRef: String,
    val approverRef: String,
    val madeAt: Instant,
    val expiresAt: Instant,
)

/** Persistence of the approvals of break-glass unlocks, in the bound tenant. */
interface BreakGlassApprovals {
    /**
     * Stores the approval of [approver] made at [madeAt]; an unconsumed one of the same approver is replaced.
     * A repeat of the same [idempotencyKey] by the same approver returns the stored approval and writes nothing.
     * Calls for one approver are serialised, so two parallel approvals never both insert.
     */
    fun replace(
        approver: String,
        idempotencyKey: UUID,
        madeAt: Instant,
        window: Duration,
    ): StoredApproval

    /**
     * Consumes, with one `UPDATE ... RETURNING`, the unconsumed approvals of [approvers] made strictly after [after],
     * for the session [unlockRef] at [consumedAt]; returns how many rows it consumed.
     */
    fun consume(
        approvers: Collection<String>,
        after: Instant,
        unlockRef: String,
        consumedAt: Instant,
    ): Int
}

/** The approval a [BreakGlassApprovals.replace] returns; [repeated] is true when the Idempotency-Key was seen. */
data class StoredApproval(
    val approval: BreakGlassApproval,
    val repeated: Boolean,
)

sealed interface ApproveBreakGlassUnlockOutcome {
    data class Approved(
        val approval: BreakGlassApproval,
    ) : ApproveBreakGlassUnlockOutcome

    /** The caller lacks `break-glass.unlock` or holds no role of the pairing: nothing is written (403). */
    data object NotPermitted : ApproveBreakGlassUnlockOutcome
}

/**
 * `approveBreakGlassUnlock` (INV-REQ-896, INV-Q-9109 A): the caller holds `break-glass.unlock` through a live
 * tenant-wide grant and a role of the VP-08 pairing; the approval is stored (replacing the caller's unconsumed one)
 * with one audit entry and no ACC-002 alert.
 */
@Suppress("LongParameterList") // one collaborator per port
class ApproveBreakGlassUnlockUseCase(
    private val tx: TransactionRunner,
    private val approvals: BreakGlassApprovals,
    private val grants: GrantRepository,
    private val settings: BreakGlassSettings,
    private val matrix: PermissionMatrix,
    private val audit: GrantAudit,
    private val clock: Clock,
    private val window: Duration,
) {
    fun handle(
        actor: String,
        tenant: TenantRef,
        idempotencyKey: UUID,
    ): ApproveBreakGlassUnlockOutcome =
        tx.inTransaction {
            val now = clock.instant()
            val held = TenantAuthority.tenantRoles(grants.findByIdentity(IdentityRef(actor)), tenant, now)
            if (!mayApprove(
                    held,
                    settings.current().pairing,
                )
            ) {
                return@inTransaction ApproveBreakGlassUnlockOutcome.NotPermitted
            }
            val stored = approvals.replace(actor, idempotencyKey, now, window)
            val approval = stored.approval
            // a repeat of the same key writes nothing: no second audit entry either
            if (!stored.repeated) {
                val target = "break-glass-approval:${approval.ref}"
                audit.record(GrantAuditEntry(actor, "approveBreakGlassUnlock", target, null, null))
            }
            ApproveBreakGlassUnlockOutcome.Approved(approval)
        }

    private fun mayApprove(
        held: Set<Role>,
        pairing: BreakGlassPairing,
    ): Boolean =
        held.any { matrix.allowance(it, BREAK_GLASS_UNLOCK) == Allowance.PERMITTED } &&
            held.any { it in pairing.first || it in pairing.second }
}
