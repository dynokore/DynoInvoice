package com.dynokore.gst.access.application.operatorsession

import com.dynokore.gst.access.application.elevation.CaseId
import com.dynokore.gst.access.application.elevation.ElevationCallers
import com.dynokore.gst.access.application.elevation.ElevationKeys
import com.dynokore.gst.access.application.elevation.Elevations
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.token.SigningKeyUnavailable
import com.dynokore.gst.access.application.token.TokenSettings
import com.dynokore.gst.access.domain.elevation.ElevationRecord
import com.dynokore.gst.access.domain.elevation.OperatorAccess
import com.dynokore.gst.access.domain.elevation.OperatorSessionAccess
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.revocation.KeyUse
import com.dynokore.gst.access.domain.revocation.VerificationKey
import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** The claims of one operator-session token (INV-Q-4119; the access contract `openOperatorSession`). */
data class OperatorSessionClaims(
    val issuer: String,
    val subject: String,
    val tenantId: String,
    val role: String,
    val case: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val tokenId: String,
)

/** An operator-session signing key opened once per token; it exposes the public side only. */
interface OperatorSigningSession {
    val kid: String
    val alg: String

    /** DER SubjectPublicKeyInfo as standard base64. */
    val publicKey: String

    fun sign(claims: OperatorSessionClaims): String
}

/**
 * The operator-session keys, read at every use (INV-Q-16473 A): [current] signs, [next] is the optional key published
 * ahead of its use. Either throws [SigningKeyUnavailable] when its key is unreadable or not a P-256 private key;
 * [next] is null only when no next key is bound.
 */
interface OperatorKeys {
    fun current(): OperatorSigningSession

    fun next(): OperatorSigningSession?
}

/**
 * Publishes operator-session keys: in one platform-bound transaction the verification-key rows of the keys not yet
 * stored and, when a row was new, the `OperatorSessionKeysChanged` record of every key still valid (INV-Q-4171).
 * Returns the stored row of each candidate, in order, so a signing happens only after the commit of its publication.
 */
fun interface OperatorKeyPublication {
    fun publish(candidates: List<VerificationKey>): List<VerificationKey>
}

/** The tenant named by [tenantRef] when it exists and is not retired, from the structure replica (INV-Q-16471 A). */
fun interface TenantDirectory {
    fun live(tenantRef: String): TenantId?
}

/** The approved elevation a session record carries (INV-Q-16496 A). */
data class ElevationWindowRecord(
    val elevationRef: String,
    val validFrom: Instant,
    val validUntil: Instant,
)

/**
 * The operator access record of one session (INV-Q-16496 A): what replicas decide a session request from. [permissions]
 * are the metadata keys of the session (never `doc.view.content`); [elevation] is present exactly when the content
 * mode is ELEVATED; [validUntil] is the token `exp`.
 */
data class OperatorAccessRecord(
    val tenant: TenantId,
    val operatorRef: String,
    val authorityVersion: Long,
    val caseRef: String,
    val role: String,
    val permissions: List<String>,
    val validUntil: Instant,
    val elevation: ElevationWindowRecord?,
)

/** Appends the operator access record in the transaction the caller holds. */
fun interface OperatorAccessPublisher {
    fun publish(record: OperatorAccessRecord)
}

/** The next value of access-svc's one authority sequence. */
fun interface AuthorityVersions {
    fun next(): Long
}

sealed interface SessionPreparation {
    /** No live platform grant holds `operator-session.open` (403 SRT-004). */
    data object Forbidden : SessionPreparation

    /** The case reference breaks the caseId rule (400 SRT-001). */
    data object BadCase : SessionPreparation

    /** The tenant does not parse, does not exist or is retired (404 SRT-005). */
    data object NoTenant : SessionPreparation

    /** The signing key cannot be used now (ISSUER_UNAVAILABLE). */
    data object Unavailable : SessionPreparation

    class Ready internal constructor(
        val actor: IdentityRef,
        val tenant: TenantId,
        val role: Role,
        val caseId: String,
        internal val session: OperatorSigningSession,
    ) : SessionPreparation
}

class OpenedSession(
    val token: String,
    val expiresAt: Instant,
    val elevated: Boolean,
)

/**
 * `openOperatorSession` (INV-REQ-862 b4, owner ruling INV-Q-4119). [prepare] runs outside any transaction: the grant
 * check on the platform grants, the case rule, the tenant, and the publication of the current and the next key (its
 * own committed transaction) before the first signing with a kid. [open] runs in the transaction bound to the chosen
 * tenant: it signs and writes the audit entry (action `openOperatorSession`, actor the operator's user ID, scope the
 * tenant, reason the case reference), so a token never leaves without its audit entry.
 */
@Suppress("LongParameterList") // one collaborator per port
class OpenOperatorSessionUseCase(
    private val callers: ElevationCallers,
    private val directory: TenantDirectory,
    private val keys: OperatorKeys,
    private val publication: OperatorKeyPublication,
    private val tx: TransactionRunner,
    private val elevations: Elevations,
    private val audit: GrantAudit,
    private val matrix: PermissionMatrix,
    private val settings: TokenSettings,
    private val clock: Clock,
    private val accessRecords: OperatorAccessPublisher,
    private val versions: AuthorityVersions,
    private val newTokenId: () -> String = { UUID.randomUUID().toString() },
) {
    fun prepare(
        actor: IdentityRef,
        tenantRef: String,
        caseId: String,
    ): SessionPreparation {
        val role = callers.operatorRole(actor, ElevationKeys.OPEN_SESSION)
        val tenant = if (role != null && CaseId.valid(caseId)) directory.live(tenantRef) else null
        return when {
            role == null -> SessionPreparation.Forbidden
            !CaseId.valid(caseId) -> SessionPreparation.BadCase
            tenant == null -> SessionPreparation.NoTenant
            else -> ready(actor, tenant, role, caseId)
        }
    }

    private fun ready(
        actor: IdentityRef,
        tenant: TenantId,
        role: Role,
        caseId: String,
    ): SessionPreparation =
        try {
            SessionPreparation.Ready(actor, tenant, role, caseId, publishKeys())
        } catch (_: SigningKeyUnavailable) {
            SessionPreparation.Unavailable
        }

    /** Publishes the current key and the optional next key; the current key must be inside its signing interval. */
    private fun publishKeys(): OperatorSigningSession {
        val now = clock.instant()
        val current = keys.current()
        val sessions = listOfNotNull(current, keys.next())
        val stored = publication.publish(sessions.map { candidate(it, now) })
        val own = stored.first()
        if (own.publicKey != current.publicKey || own.alg != current.alg) {
            throw SigningKeyUnavailable("the published key of ${current.kid} differs from the signing key")
        }
        if (!now.isBefore(own.validFrom.plus(settings.keyRotationInterval))) {
            throw SigningKeyUnavailable("the operator-session key ${current.kid} is past its signing interval")
        }
        return current
    }

    private fun candidate(
        session: OperatorSigningSession,
        now: Instant,
    ) = VerificationKey(
        kid = session.kid,
        alg = session.alg,
        publicKey = session.publicKey,
        validFrom = now,
        validUntil = now.plus(settings.keyRotationInterval).plus(settings.lifetime),
        use = KeyUse.OPERATOR_SESSION,
    )

    /** Call inside a transaction bound to [SessionPreparation.Ready.tenant]. */
    fun open(ready: SessionPreparation.Ready): OpenedSession =
        tx.inTransaction {
            val at = clock.instant()
            // a JWT holds whole seconds: the record's validUntil is then the token exp exactly
            val now = at.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            val expires = now.plus(settings.lifetime)
            val token =
                ready.session.sign(
                    OperatorSessionClaims(
                        issuer = settings.issuer,
                        subject = ready.actor.value,
                        tenantId = ready.tenant.toString(),
                        role = ready.role.wire,
                        case = ready.caseId,
                        issuedAt = now,
                        expiresAt = expires,
                        tokenId = newTokenId(),
                    ),
                )
            audit.record(
                GrantAuditEntry(
                    actor = ready.actor.value,
                    action = ACTION,
                    scope = "tenant:${ready.tenant}",
                    reason = ready.caseId,
                    priorStateJson = null,
                ),
            )
            val elevation = openElevation(ready, at)
            accessRecords.publish(accessRecord(ready, expires, elevation))
            OpenedSession(token, expires, elevation != null)
        }

    private fun accessRecord(
        ready: SessionPreparation.Ready,
        expires: Instant,
        elevation: ElevationRecord?,
    ) = OperatorAccessRecord(
        tenant = ready.tenant,
        operatorRef = ready.actor.value,
        authorityVersion = versions.next(),
        caseRef = ready.caseId,
        role = ready.role.wire,
        permissions = OperatorSessionAccess.metadataKeys { matrix.allowance(ready.role, it) }.map { it.value },
        validUntil = expires,
        elevation =
            elevation?.let {
                val box = checkNotNull(it.window)
                ElevationWindowRecord(it.ref, box.from, checkNotNull(box.until))
            },
    )

    /**
     * The approved elevation whose time box the session carries: present exactly when `doc.view.content` is allowed
     * to this session now, which is also when the content mode is ELEVATED.
     */
    private fun openElevation(
        ready: SessionPreparation.Ready,
        at: Instant,
    ): ElevationRecord? {
        val canElevate = matrix.allowance(ready.role, ElevationKeys.REQUEST) == Allowance.PERMITTED
        val record =
            if (canElevate) {
                OperatorAccess.openElevation(
                    elevations.findByRequester(ready.actor),
                    ready.actor,
                    TenantRef(ready.tenant.toString()),
                    at,
                )
            } else {
                null
            }
        val allowed =
            OperatorSessionAccess.allows(
                { matrix.allowance(ready.role, it) },
                OperatorAccess.OPENED_BY_ELEVATION.single(),
                record != null,
            )
        return record.takeIf { allowed }
    }

    companion object {
        /** The audit action: the operation name, which `gst.security-events.mapping` maps to class `elevation`. */
        const val ACTION = "openOperatorSession"
    }
}
