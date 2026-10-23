package com.dynokore.gst.access.application.breakglass

import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.grant.GrantRepository
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

    /** Stores one action of an open session and returns its reference. */
    fun insertAction(
        unlockRef: String,
        action: BreakGlassAction,
        target: String?,
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
}

sealed interface UnlockBreakGlassOutcome {
    data class Unlocked(
        val session: BreakGlassSession,
    ) : UnlockBreakGlassOutcome

    /** Fewer than two different people were named (INV-AC-2253, INV-AC-8563). */
    data object PairOfOne : UnlockBreakGlassOutcome

    /** Two people, but the roles of the pairing are not held by two different people (INV-REQ-1269). */
    data object PairNotHeld : UnlockBreakGlassOutcome
}

/**
 * `unlockBreakGlass` (INV-REQ-896, INV-REQ-1269, INV-REQ-897): refuses fewer than two different people and a pair that
 * does not hold the roles of the VP-08 pairing; otherwise opens a session whose length is the VP-08 value (never the
 * caller's), raises ACC-002 and writes the audit entry in the same transaction.
 */
class UnlockBreakGlassUseCase(
    private val tx: TransactionRunner,
    private val sessions: BreakGlassSessions,
    private val grants: GrantRepository,
    private val settings: BreakGlassSettings,
    private val effects: BreakGlassEffects,
    private val clock: Clock,
) {
    fun handle(
        actor: String,
        tenant: TenantRef,
        approvedBy: List<IdentityRef>,
    ): UnlockBreakGlassOutcome {
        val people = approvedBy.distinct()
        if (people.size < 2) return UnlockBreakGlassOutcome.PairOfOne
        return tx.inTransaction {
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
            val hours = setting.hours
            check(BreakGlassRules.isSessionHours(hours)) { "the break-glass session length must be 1 to 8 hours" }
            val session = sessions.insert(hours, now, people.map { it.value })
            effects.record(actor, "unlockBreakGlass", session.ref, UNLOCK_ACTION)
            UnlockBreakGlassOutcome.Unlocked(session)
        }
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
    ): RecordBreakGlassOutcome =
        tx.inTransaction {
            val session = sessions.find(unlockRef) ?: return@inTransaction RecordBreakGlassOutcome.NotFound
            if (!session.isOpenAt(clock.instant())) return@inTransaction RecordBreakGlassOutcome.NotOpen
            val ref = sessions.insertAction(unlockRef, action, target)
            effects.record(actor, "recordBreakGlassAction", unlockRef, action.name, action.name)
            RecordBreakGlassOutcome.Recorded(ref)
        }
}

/** The expiry of a session unlocked at [unlockedAt] for [hours]. */
fun expiryOf(
    unlockedAt: Instant,
    hours: Int,
): Instant = unlockedAt.plus(Duration.ofHours(hours.toLong()))
