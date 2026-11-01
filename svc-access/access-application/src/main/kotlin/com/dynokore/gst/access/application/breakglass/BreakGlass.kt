package com.dynokore.gst.access.application.breakglass

import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.idp.IdentityProviderAvailability
import com.dynokore.gst.access.domain.breakglass.BreakGlassAction
import com.dynokore.gst.access.domain.breakglass.BreakGlassPairing
import com.dynokore.gst.access.domain.breakglass.BreakGlassRules
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.TenantAuthority
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The ACC code every unlock and every action under break-glass raises (INV-REQ-897 behaviour 1). */
const val ACC_BREAK_GLASS = "ACC-002"

/** One unlock of the sealed account: who approved it, how long it lasts and whether it is still open. */
data class BreakGlassSession(
    val ref: String,
    val tenantRef: String,
    val sessionHours: Int,
    val unlockedAt: Instant,
    val expiresAt: Instant,
    val approvedBy: List<String>,
    val sealed: Boolean,
) {
    /** Open: not resealed and [at] before the expiry (a call at the expiry instant is already refused). */
    fun isOpenAt(at: Instant): Boolean = !sealed && at.isBefore(expiresAt)
}

/** Persistence of break-glass sessions and of the actions recorded under them, in the bound tenant. */
interface BreakGlassSessions {
    fun insert(
        sessionHours: Int,
        unlockedAt: Instant,
        approvedBy: List<String>,
    ): BreakGlassSession

    fun find(ref: String): BreakGlassSession?

    /** Marks the session of [ref] resealed (closed). */
    fun markSealed(ref: String)

    /** Stores one action of an open session and returns its reference. */
    fun insertAction(
        unlockRef: String,
        action: BreakGlassAction,
        target: String?,
        callerService: String,
    ): String
}

/** What the VP-08 setting of the bound tenant says: the session length (1 to 8 hours, 4 by default) and the pairing. */
data class BreakGlassSetting(
    val hours: Int,
    val pairing: BreakGlassPairing,
)

/** access-svc reads the setting from VP-08, never from the caller. */
fun interface BreakGlassSettings {
    fun current(): BreakGlassSetting
}

/**
 * Raises ACC-002 as the platform event `BreakGlassAlertRaised` in the command transaction (INV-REQ-897, INV-Q-8839 A):
 * [action] is UNLOCK or the break-glass action name, [unlockRef] the session the alert belongs to.
 */
fun interface BreakGlassAlerts {
    fun raise(
        code: String,
        action: String,
        unlockRef: String,
    )

    /**
     * Raises the alert of an unlock that carries [overrideReason] (INV-Q-11557 A). The default drops the reason; an
     * adapter that publishes it overrides this method.
     */
    fun raise(
        code: String,
        action: String,
        unlockRef: String,
        overrideReason: String?,
    ) = raise(code, action, unlockRef)
}

sealed interface UnlockBreakGlassOutcome {
    data class Unlocked(
        val session: BreakGlassSession,
    ) : UnlockBreakGlassOutcome

    /** Fewer than two different people were named (INV-AC-2253, INV-AC-8563). */
    data object PairOfOne : UnlockBreakGlassOutcome

    /** Two people, but the roles of the pairing are not held by two different people (INV-REQ-1269). */
    data object PairNotHeld : UnlockBreakGlassOutcome

    /**
     * The identity provider counts as available and the request carries no `IDP_PARTIAL_OUTAGE` override reason
     * (INV-Q-11557 A, INV-AC-9185): nothing is written (409).
     */
    data object IdpAvailable : UnlockBreakGlassOutcome

    /**
     * A named approver other than the caller has no unconsumed approval made within the approval window (INV-Q-9109
     * A, INV-AC-9302): nothing is written and no approval is consumed (403).
     */
    data object ApprovalMissing : UnlockBreakGlassOutcome
}

/** The one override reason that opens an unlock while the identity provider counts as available (INV-Q-11557 A). */
const val IDP_PARTIAL_OUTAGE = "IDP_PARTIAL_OUTAGE"

/**
 * `unlockBreakGlass` (INV-REQ-896, INV-REQ-1269, INV-REQ-897): refuses fewer than two different people and a pair that
 * does not hold the roles of the VP-08 pairing, and (INV-Q-11557 A) an unlock while the identity provider counts as
 * available unless the override reason is `IDP_PARTIAL_OUTAGE`; otherwise opens a session whose length is the VP-08
 * value (never the caller's), raises ACC-002 and writes the audit entry in the same transaction.
 */
@Suppress("LongParameterList") // one collaborator per port
class UnlockBreakGlassUseCase(
    private val tx: TransactionRunner,
    private val sessions: BreakGlassSessions,
    private val grants: GrantRepository,
    private val settings: BreakGlassSettings,
    private val effects: BreakGlassEffects,
    private val clock: Clock,
    private val availability: IdentityProviderAvailability,
    private val approvals: BreakGlassApprovals,
    private val approvalWindow: Duration,
) {
    /** Thrown inside the transaction when a named approver has no approval left, so that the session row rolls back. */
    private class ApprovalShortfall : RuntimeException(null, null, false, false)

    fun handle(
        actor: String,
        tenant: TenantRef,
        approvedBy: List<IdentityRef>,
        overrideReason: String? = null,
    ): UnlockBreakGlassOutcome {
        val people = approvedBy.distinct()
        if (people.size < 2) return UnlockBreakGlassOutcome.PairOfOne
        return try {
            open(actor, tenant, people, overrideReason)
        } catch (_: ApprovalShortfall) {
            UnlockBreakGlassOutcome.ApprovalMissing
        }
    }

    private fun open(
        actor: String,
        tenant: TenantRef,
        people: List<IdentityRef>,
        overrideReason: String?,
    ): UnlockBreakGlassOutcome =
        tx.inTransaction {
            val now = clock.instant()
            val setting = settings.current()
            val roles = people.associateWith { TenantAuthority.tenantRoles(grants.findByIdentity(it), tenant, now) }
            if (!BreakGlassRules.pairHolds(
                    roles,
                    setting.pairing,
                )
            ) {
                return@inTransaction UnlockBreakGlassOutcome.PairNotHeld
            }
            // exact equality: `idp_partial_outage` or a trailing space never counts as the override
            val overridden = overrideReason == IDP_PARTIAL_OUTAGE
            val available = availability.isAvailable()
            if (available && !overridden) return@inTransaction UnlockBreakGlassOutcome.IdpAvailable
            // while the provider counts as unavailable the reason is ignored and not recorded
            val recordedReason = if (available) IDP_PARTIAL_OUTAGE else null
            val hours = setting.hours
            check(BreakGlassRules.isSessionHours(hours)) { "the break-glass session length must be 1 to 8 hours" }
            val session = sessions.insert(hours, now, people.map { it.value })
            // every refusal is decided above; the approvals of the others go in this transaction, all or none
            val others = people.map { it.value }.filter { it != actor }
            if (approvals.consume(others, now.minus(approvalWindow), session.ref, now) != others.size) {
                throw ApprovalShortfall()
            }
            effects.recordUnlock(actor, session.ref, recordedReason)
            UnlockBreakGlassOutcome.Unlocked(session)
        }
}

/** The ACC-002 alert and the audit entry every unlock and action carries (INV-REQ-897 behaviours 1 and 2). */
class BreakGlassEffects(
    private val alerts: BreakGlassAlerts,
    private val audit: GrantAudit,
) {
    fun record(
        actor: String,
        operation: String,
        unlockRef: String,
        alertAction: String,
        detail: String? = null,
    ) {
        alerts.raise(ACC_BREAK_GLASS, alertAction, unlockRef)
        audit.record(GrantAuditEntry(actor, operation, "break-glass:$unlockRef", detail, null))
    }

    /** The alert and the audit entry of an unlock; [overrideReason] is both the audit detail and an alert property. */
    fun recordUnlock(
        actor: String,
        unlockRef: String,
        overrideReason: String?,
    ) {
        alerts.raise(ACC_BREAK_GLASS, UNLOCK_ACTION, unlockRef, overrideReason)
        audit.record(GrantAuditEntry(actor, "unlockBreakGlass", "break-glass:$unlockRef", overrideReason, null))
    }
}

/** The `action` of the alert an unlock raises; the alerts of recorded actions carry the action's own name. */
const val UNLOCK_ACTION = "UNLOCK"

sealed interface RecordBreakGlassOutcome {
    data class Recorded(
        val actionRef: String,
    ) : RecordBreakGlassOutcome

    data object NotFound : RecordBreakGlassOutcome

    /** The session is resealed or its expiry has passed (409). */
    data object NotOpen : RecordBreakGlassOutcome
}

/** `recordBreakGlassAction` (INV-REQ-897): one ACC-002 and one audit entry per action of an open session. */
class RecordBreakGlassActionUseCase(
    private val tx: TransactionRunner,
    private val sessions: BreakGlassSessions,
    private val effects: BreakGlassEffects,
    private val clock: Clock,
) {
    fun handle(
        actor: String,
        unlockRef: String,
        action: BreakGlassAction,
        target: String?,
        callerService: String,
    ): RecordBreakGlassOutcome =
        tx.inTransaction {
            val session = sessions.find(unlockRef) ?: return@inTransaction RecordBreakGlassOutcome.NotFound
            if (!session.isOpenAt(clock.instant())) return@inTransaction RecordBreakGlassOutcome.NotOpen
            val ref = sessions.insertAction(unlockRef, action, target, callerService)
            // the audit entry names the service that carried the action out (INV-Q-9113 A)
            effects.record(actor, "recordBreakGlassAction", unlockRef, action.name, "${action.name} by $callerService")
            RecordBreakGlassOutcome.Recorded(ref)
        }
}

/** The expiry of a session unlocked at [unlockedAt] for [hours]. */
fun expiryOf(
    unlockedAt: Instant,
    hours: Int,
): Instant = unlockedAt.plus(Duration.ofHours(hours.toLong()))

sealed interface SignInBreakGlassOutcome {
    data class SignedIn(
        val session: BreakGlassSession,
    ) : SignInBreakGlassOutcome

    data object NotFound : SignInBreakGlassOutcome

    /** No open unlock: the session is resealed or its expiry has passed (409). */
    data object NotOpen : SignInBreakGlassOutcome

    /** A wrong sealing credential, or a tenant with no account row (403). */
    data object WrongCredential : SignInBreakGlassOutcome
}

/**
 * `signInBreakGlass` (INV-REQ-896, INV-REQ-1262, INV-Q-8661 A): the session must exist and be open (a call at the
 * expiry instant is already refused), then the digest of the presented credential is compared with the stored
 * verifier in constant time. No account row is a wrong credential. Nothing is written.
 */
class SignInBreakGlassUseCase(
    private val tx: TransactionRunner,
    private val sessions: BreakGlassSessions,
    private val keeper: SealedAccountKeeper,
    private val clock: Clock,
) {
    fun handle(
        unlockRef: String,
        sealingCredential: String,
    ): SignInBreakGlassOutcome =
        tx.inTransaction {
            val session = sessions.find(unlockRef) ?: return@inTransaction SignInBreakGlassOutcome.NotFound
            when {
                !session.isOpenAt(clock.instant()) -> SignInBreakGlassOutcome.NotOpen
                !keeper.matches(sealingCredential) -> SignInBreakGlassOutcome.WrongCredential
                else -> SignInBreakGlassOutcome.SignedIn(session)
            }
        }
}

sealed interface ResealBreakGlassOutcome {
    /** Closed by this call; [credential] is the new sealing credential, null when no account row exists. */
    data class Resealed(
        val session: BreakGlassSession,
        val credential: SealingCredential?,
    ) : ResealBreakGlassOutcome

    /** The session was closed by an earlier call: the original data, no credential, nothing changed. */
    data class AlreadySealed(
        val session: BreakGlassSession,
    ) : ResealBreakGlassOutcome

    data object NotFound : ResealBreakGlassOutcome

    /** The session is not open: its expiry has passed and it was not resealed (409). */
    data object NotOpen : ResealBreakGlassOutcome
}

/**
 * `resealBreakGlass` (INV-REQ-897 behaviour 4, INV-Q-8837 A): closes an open session, replaces the sealing credential
 * and returns the new one once. A session that is sealed already answers its data and changes nothing; a session
 * past its expiry that was never sealed is refused. The caller's role is checked by the inbound adapter.
 */
class ResealBreakGlassUseCase(
    private val tx: TransactionRunner,
    private val sessions: BreakGlassSessions,
    private val keeper: SealedAccountKeeper,
    private val audit: GrantAudit,
    private val clock: Clock,
) {
    fun handle(
        actor: String,
        unlockRef: String,
    ): ResealBreakGlassOutcome =
        tx.inTransaction {
            val session = sessions.find(unlockRef) ?: return@inTransaction ResealBreakGlassOutcome.NotFound
            when {
                session.sealed -> {
                    ResealBreakGlassOutcome.AlreadySealed(session)
                }

                !session.isOpenAt(clock.instant()) -> {
                    ResealBreakGlassOutcome.NotOpen
                }

                else -> {
                    val credential = keeper.rotate()
                    sessions.markSealed(unlockRef)
                    audit.record(GrantAuditEntry(actor, "resealBreakGlass", "break-glass:$unlockRef", null, null))
                    ResealBreakGlassOutcome.Resealed(session.copy(sealed = true), credential)
                }
            }
        }
}
