package com.dynokore.gst.masterdata.application.connection

import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Reads the secret store. The value is read at call time, only to prove the reference resolves; it is cleared at once
 * and never stored, logged or returned. A reference that does not resolve gives null.
 */
fun interface SecretStore {
    fun read(credentialRef: String): CharArray?
}

/**
 * The overlap length (`masterdata.connections.overlap`). No default in code: null means unset and rotation fails
 * closed. The value is bound by INV-T-18776.
 */
data class RotationSettings(
    val overlap: Duration?,
)

/** What a finished rotation did; kept so that a repeat returns it unchanged. */
data class Rotated(
    val connectionRef: String,
    val currentRef: String,
    val previousRef: String,
    val overlapEnd: Instant,
    val rotatedAt: Instant,
)

/** The current and previous credential reference of a connection, the overlap end, and the rotations already done. */
data class CredentialState(
    val connectionRef: String,
    val currentRef: String,
    val previousRef: String?,
    val overlapEnd: Instant?,
    val rotatedAt: Instant?,
    val done: Map<String, Rotated> = emptyMap(),
)

interface CredentialStateStore {
    fun find(connectionRef: String): CredentialState?

    fun save(state: CredentialState)
}

sealed interface RotateCredentialOutcome {
    /** [repeat] is true when (connectionRef, credentialRef) was rotated before; [rotation] is then the original. */
    data class Rotated(
        val rotation: com.dynokore.gst.masterdata.application.connection.Rotated,
        val repeat: Boolean,
    ) : RotateCredentialOutcome

    data object Forbidden : RotateCredentialOutcome

    data object Unknown : RotateCredentialOutcome

    /** `masterdata.connections.overlap` is unset: fail closed, nothing changes. */
    data object OverlapNotConfigured : RotateCredentialOutcome

    /** The replacement reference does not resolve in the secret store; nothing changes. */
    data object ReplacementUnavailable : RotateCredentialOutcome

    /** The replacement is the reference already current and was not rotated to; nothing changes. */
    data object SameAsCurrent : RotateCredentialOutcome
}

/** The scoped permission `connection.configure` is reused for rotation (INV-Q-10027). */
class RotateCredential(
    private val tx: TransactionRunner,
    private val store: ConnectionStore,
    private val states: CredentialStateStore,
    private val secrets: SecretStore,
    private val permission: ConnectionConfigurePermission,
    private val settings: RotationSettings,
    private val clock: Clock,
) {
    fun handle(
        caller: PersonRef,
        connectionRef: String,
        credentialRef: String,
    ): RotateCredentialOutcome =
        tx.inTransaction {
            if (!permission.allows(caller)) return@inTransaction RotateCredentialOutcome.Forbidden
            val overlap = settings.overlap ?: return@inTransaction RotateCredentialOutcome.OverlapNotConfigured
            val record = store.find(connectionRef) ?: return@inTransaction RotateCredentialOutcome.Unknown
            val state =
                states.find(connectionRef) ?: CredentialState(connectionRef, record.credentialRef, null, null, null)
            state.done[credentialRef]?.let { return@inTransaction RotateCredentialOutcome.Rotated(it, repeat = true) }
            if (credentialRef == state.currentRef) return@inTransaction RotateCredentialOutcome.SameAsCurrent
            val value =
                secrets.read(credentialRef) ?: return@inTransaction RotateCredentialOutcome.ReplacementUnavailable
            value.fill('\u0000')
            val now = clock.instant()
            val rotated = Rotated(connectionRef, credentialRef, state.currentRef, now.plus(overlap), now)
            states.save(
                CredentialState(
                    connectionRef,
                    credentialRef,
                    state.currentRef,
                    rotated.overlapEnd,
                    now,
                    state.done + (credentialRef to rotated),
                ),
            )
            RotateCredentialOutcome.Rotated(rotated, repeat = false)
        }
}

/**
 * Chooses the credential reference of a call. A call takes its reference when it starts ([begin]) and finishes on it
 * while [accepts] holds: the current reference always, the previous one only before the overlap end; after the overlap
 * end the previous reference is refused and dropped.
 */
class CredentialGate(
    private val tx: TransactionRunner,
    private val store: ConnectionStore,
    private val states: CredentialStateStore,
    private val clock: Clock,
) {
    fun begin(connectionRef: String): String? =
        tx.inTransaction {
            states.find(connectionRef)?.currentRef ?: store.find(connectionRef)?.credentialRef
        }

    fun accepts(
        connectionRef: String,
        credentialRef: String,
    ): Boolean =
        tx.inTransaction {
            val state = states.find(connectionRef)
            if (state == null) {
                store.find(connectionRef)?.credentialRef == credentialRef
            } else if (credentialRef == state.currentRef) {
                true
            } else if (credentialRef == state.previousRef) {
                val end = state.overlapEnd
                if (end != null && clock.instant().isBefore(end)) {
                    true
                } else {
                    states.save(state.copy(previousRef = null, overlapEnd = null))
                    false
                }
            } else {
                false
            }
        }
}

/** One connection credential with an expiry (`credentialExpiresAt`). */
data class CredentialExpiry(
    val connectionRef: String,
    val credentialRef: String,
    val credentialExpiresAt: Instant,
)

/** The connections that have a `credentialExpiresAt`. */
fun interface CredentialExpiries {
    fun all(): List<CredentialExpiry>
}

/** The reminder offsets in days of VP-68 for the tenant; the preset 14 and 3 applies when no tenant value is set. */
fun interface ReminderOffsets {
    fun days(): List<Int>
}

/** Which (connection, credential reference, offset) reminders were already requested. */
interface ReminderLog {
    fun wasReminded(
        connectionRef: String,
        credentialRef: String,
        offsetDays: Int,
    ): Boolean

    fun markReminded(
        connectionRef: String,
        credentialRef: String,
        offsetDays: Int,
    )
}

data class ConnectionCredentialExpiryReminderRequested(
    val connectionRef: String,
    val credentialRef: String,
    val offsetDays: Int,
    val credentialExpiresAt: Instant,
)

/** Requests the reminder event; the publish itself is wired by INV-T-18776. */
fun interface CredentialExpiryReminderPublisher {
    fun publish(event: ConnectionCredentialExpiryReminderRequested)
}

/**
 * INV-REQ-837: for each credential with an expiry and each offset whose day has been reached
 * (`now >= expiresAt - offset days`) and not yet reminded, requests one reminder. The code holds no number of days.
 * When several offsets are due on a first run, each is requested once.
 */
class CheckCredentialExpiry(
    private val tx: TransactionRunner,
    private val expiries: CredentialExpiries,
    private val offsets: ReminderOffsets,
    private val log: ReminderLog,
    private val publisher: CredentialExpiryReminderPublisher,
    private val clock: Clock,
) {
    /** Returns the reminders requested by this run. */
    fun run(): List<ConnectionCredentialExpiryReminderRequested> =
        tx.inTransaction {
            val now = clock.instant()
            val days = offsets.days().distinct().sortedDescending()
            val requested = mutableListOf<ConnectionCredentialExpiryReminderRequested>()
            for (e in expiries.all()) {
                for (d in days) {
                    val due = !now.isBefore(e.credentialExpiresAt.minus(d.toLong(), ChronoUnit.DAYS))
                    if (due && now.isBefore(e.credentialExpiresAt) &&
                        !log.wasReminded(e.connectionRef, e.credentialRef, d)
                    ) {
                        val event =
                            ConnectionCredentialExpiryReminderRequested(
                                e.connectionRef,
                                e.credentialRef,
                                d,
                                e.credentialExpiresAt,
                            )
                        publisher.publish(event)
                        log.markReminded(e.connectionRef, e.credentialRef, d)
                        requested += event
                    }
                }
            }
            requested
        }
}
