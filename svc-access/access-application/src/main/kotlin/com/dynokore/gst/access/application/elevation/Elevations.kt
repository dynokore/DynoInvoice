package com.dynokore.gst.access.application.elevation

import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.domain.elevation.ElevationAsk
import com.dynokore.gst.access.domain.elevation.ElevationKind
import com.dynokore.gst.access.domain.elevation.ElevationRecord
import com.dynokore.gst.access.domain.elevation.ElevationStatus
import com.dynokore.gst.access.domain.grant.Decision
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.PlatformAuthorisation
import com.dynokore.gst.access.domain.grant.TenantAuthority
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Duration

/** The permission keys the five operations ask for, compared by equality (INV-Q-15950 A); no code compares a role. */
object ElevationKeys {
    val REQUEST = Permission("elevation.request")
    val DECIDE = Permission("elevation.decide")
    val INVITE = Permission("diagnostic.invite")
}

/** Persistence of elevation requests and diagnostic invitations under the bound tenant (row-level security). */
interface Elevations {
    /** The pending record of the same requester, kind, subject and case in the bound tenant: the repeat key. */
    fun findPending(ask: ElevationAsk): ElevationRecord?

    /** Stores a new pending record for the bound tenant under a fresh reference. */
    fun insert(ask: ElevationAsk): ElevationRecord

    /** The record with [ref] locked `FOR UPDATE` until the transaction ends, or null (also for another tenant). */
    fun lock(ref: String): ElevationRecord?

    fun update(
        before: ElevationRecord,
        after: ElevationRecord,
    )

    /** Every record of [requester] in the bound tenant, any status. */
    fun findByRequester(requester: IdentityRef): List<ElevationRecord>
}

/**
 * Whether a caller holds a permission key, read live from this service's own grant table (it is the authority;
 * INV-REQ-1214). The operator check reads the platform grants; the tenant check reads the grants of the bound tenant.
 */
class ElevationCallers(
    private val platform: com.dynokore.gst.access.application.authorise.PlatformRead,
    private val tx: TransactionRunner,
    private val grants: GrantRepository,
    private val matrix: PermissionMatrix,
    private val clock: Clock,
) {
    fun operatorHolds(
        actor: IdentityRef,
        permission: Permission,
    ): Boolean {
        val held = platform.read { grants.findByIdentity(actor) }
        return PlatformAuthorisation.decide(held, permission, matrix, clock.instant()) is Decision.Allow
    }

    /** True when [actor] holds [permission] through a live grant covering all of [tenant], the bound tenant. */
    fun tenantHolds(
        actor: IdentityRef,
        tenant: TenantRef,
        permission: Permission,
    ): Boolean {
        val held = tx.inTransaction { grants.findByIdentity(actor) }
        return TenantAuthority.holds(held, tenant, permission, matrix, clock.instant())
    }
}

/** The case rule (INV-Q-4529, INV-Q-4534): 1 to 64 code points, one outside the whitespace set, no ':' or control. */
object CaseId {
    private const val MAX = 64
    private val WHITESPACE: Set<Int> =
        setOf(0x20, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF) + (0x2000..0x200A)

    fun valid(text: String): Boolean {
        val points = text.codePoints().toArray()
        return points.size in 1..MAX &&
            points.none { it == ':'.code || Character.isISOControl(it) } &&
            points.any { it !in WHITESPACE }
    }
}

sealed interface ElevationOutcome {
    data class Done(
        val record: ElevationRecord,
        val repeated: Boolean = false,
    ) : ElevationOutcome

    /** No such record in the bound tenant, or of another kind, or of another requester. */
    data object NotFound : ElevationOutcome

    /** The record is no longer pending. */
    data object NotPending : ElevationOutcome

    data object BadCase : ElevationOutcome

    data object BadDecision : ElevationOutcome
}

@Suppress("LongParameterList") // the fields of one audit entry
private fun audit(
    audit: GrantAudit,
    actor: IdentityRef,
    action: String,
    record: ElevationRecord,
    reason: String?,
    prior: ElevationStatus?,
) = audit.record(
    GrantAuditEntry(
        actor = actor.value,
        action = action,
        scope = "tenant:${record.tenant.value}",
        reason = reason,
        priorStateJson = prior?.let { """{"ref":"${record.ref}","status":"${it.name}"}""" },
    ),
)

/**
 * `requestElevation` (kind ELEVATION, subject the tenant) and `inviteDiagnostics` (kind DIAGNOSTIC_INVITATION,
 * subject the installation): a pending record in the bound tenant, audited. A repeat while one is pending returns it.
 */
class OpenElevationUseCase(
    private val tx: TransactionRunner,
    private val elevations: Elevations,
    private val audit: GrantAudit,
) {
    fun handle(
        requester: IdentityRef,
        ask: ElevationAsk,
        reason: String?,
    ): ElevationOutcome {
        if (!CaseId.valid(ask.caseId)) return ElevationOutcome.BadCase
        return tx.inTransaction {
            val existing = elevations.findPending(ask)
            if (existing != null) return@inTransaction ElevationOutcome.Done(existing, repeated = true)
            val created = elevations.insert(ask)
            val action = if (ask.kind == ElevationKind.ELEVATION) "requestElevation" else "inviteDiagnostics"
            audit(audit, requester, action, created, "case ${ask.caseId}" + reason?.let { ": $it" }.orEmpty(), null)
            ElevationOutcome.Done(created)
        }
    }
}

/** `decideElevation`: grant for a time box or refuse a pending record of the bound tenant; audited. */
class DecideElevationUseCase(
    private val tx: TransactionRunner,
    private val elevations: Elevations,
    private val audit: GrantAudit,
    private val clock: Clock,
) {
    fun handle(
        approver: IdentityRef,
        ref: String,
        grant: Boolean,
        validForMinutes: Int?,
    ): ElevationOutcome {
        if (grant && (validForMinutes == null || validForMinutes < 1)) return ElevationOutcome.BadDecision
        return tx.inTransaction {
            val before = elevations.lock(ref) ?: return@inTransaction ElevationOutcome.NotFound
            val after =
                if (grant) {
                    before.granted(
                        approver,
                        clock.instant(),
                        Duration.ofMinutes(checkNotNull(validForMinutes).toLong()),
                    )
                } else {
                    before.refused(approver)
                } ?: return@inTransaction ElevationOutcome.NotPending
            elevations.update(before, after)
            val action = if (grant) "approveElevation" else "refuseElevation"
            val until = after.window?.until?.let { "until $it" }
            audit(audit, approver, action, after, until, before.status)
            ElevationOutcome.Done(after)
        }
    }
}

/**
 * `withdrawElevationRequest` / `withdrawDiagnosticInvitation`: cancels a pending record of the given kind that [actor]
 * requested; a repeat on a withdrawn record is a no-op (API-15).
 */
class WithdrawElevationUseCase(
    private val tx: TransactionRunner,
    private val elevations: Elevations,
    private val audit: GrantAudit,
) {
    fun handle(
        actor: IdentityRef,
        ref: String,
        kind: ElevationKind,
    ): ElevationOutcome =
        tx.inTransaction {
            val before =
                elevations.lock(ref)?.takeIf { it.kind == kind && it.requester == actor }
                    ?: return@inTransaction ElevationOutcome.NotFound
            if (before.status == ElevationStatus.WITHDRAWN) return@inTransaction ElevationOutcome.Done(before, true)
            val after = before.withdrawn() ?: return@inTransaction ElevationOutcome.NotPending
            elevations.update(before, after)
            val action =
                if (kind ==
                    ElevationKind.ELEVATION
                ) {
                    "withdrawElevationRequest"
                } else {
                    "withdrawDiagnosticInvitation"
                }
            audit(audit, actor, action, after, null, before.status)
            ElevationOutcome.Done(after)
        }
}
