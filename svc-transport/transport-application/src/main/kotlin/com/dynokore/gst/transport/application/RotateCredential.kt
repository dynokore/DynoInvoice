package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AdapterId
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The reference of a credential in the vault (INV-REQ-837). Never the secret itself. */
@JvmInline
value class CredentialRef(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "credential reference must not be blank" }
    }
}

/** A secret read from the vault at call time. It never prints: [toString] is redacted. */
class Secret(
    private val bytes: CharArray,
) {
    fun reveal(): CharArray = bytes.copyOf()

    override fun toString(): String = "Secret(***)"
}

/** The vault port: the one place a secret is read, by reference and at call time. */
interface CredentialVault {
    /** True when the vault holds a secret under [ref]; the value is not read. */
    fun holds(ref: CredentialRef): Boolean

    /** Reads the secret for [ref] at call time; null when the vault holds none. */
    fun read(ref: CredentialRef): Secret?
}

/** The credential state held per adapter: the current reference, the previous one and the end of the overlap. */
data class AdapterCredentialState(
    val current: CredentialRef,
    val previous: CredentialRef? = null,
    val overlapEnd: Instant? = null,
) {
    init {
        require((previous == null) == (overlapEnd == null)) { "previous reference and overlap end go together" }
    }

    /** True while the previous reference may still be used. */
    fun overlapOpen(at: Instant): Boolean = previous != null && overlapEnd != null && at.isBefore(overlapEnd)
}

interface AdapterCredentialStates {
    fun load(adapter: AdapterId): AdapterCredentialState?

    fun save(
        adapter: AdapterId,
        state: AdapterCredentialState,
    )
}

/** What a rotation did. Stored by (adapter, credentialId): a repeat returns it. No secret value is part of it. */
data class RotationOutcome(
    val adapter: AdapterId,
    val credentialId: CredentialRef,
    val previous: CredentialRef,
    val overlapEnd: Instant,
)

/** The audit record of a rotation: the acting user ID, never a role. */
data class CredentialRotationAudit(
    val actingUserId: String,
    val outcome: RotationOutcome,
    val at: Instant,
)

/** The rotation log: the outcome per (adapter, credentialId) and the audit record, stored in one place. */
interface CredentialRotationLog {
    fun find(
        adapter: AdapterId,
        credentialId: CredentialRef,
    ): RotationOutcome?

    fun record(audit: CredentialRotationAudit)
}

enum class RotationRefusal { NO_WINDOW, UNKNOWN_REFERENCE, SAME_AS_CURRENT, ROTATION_IN_PROGRESS }

sealed interface RotateResult {
    /** [repeat] is true when the original outcome of an earlier identical call is returned. */
    data class Rotated(
        val outcome: RotationOutcome,
        val repeat: Boolean,
    ) : RotateResult

    data class Refused(
        val reason: RotationRefusal,
    ) : RotateResult
}

/**
 * Rotates the credential of an AP adapter with an overlap window (INV-REQ-837, pattern of INV-REQ-880): the new
 * reference becomes current at once, the previous one stays usable until the overlap end, then it is dropped.
 * The window is a parameter supplied by the caller; this module holds no number and no default. The permission
 * `ap-credential.manage` is asked in the HTTP layer.
 */
class RotateCredential(
    private val states: AdapterCredentialStates,
    private val vault: CredentialVault,
    private val log: CredentialRotationLog,
    private val clock: Clock,
) {
    fun rotate(
        adapter: AdapterId,
        credentialId: CredentialRef,
        window: Duration?,
        actingUserId: String,
    ): RotateResult {
        if (window == null || window.isZero || window.isNegative) return RotateResult.Refused(RotationRefusal.NO_WINDOW)
        require(actingUserId.isNotBlank()) { "the acting user ID is required" }
        val state = states.load(adapter) ?: throw AdapterNotRegistered(adapter.value)
        val earlier = log.find(adapter, credentialId)
        val now = clock.instant()
        val refusal = if (earlier != null) null else refusalFor(state, credentialId, now)
        return when {
            earlier != null -> RotateResult.Rotated(earlier, repeat = true)
            refusal != null -> RotateResult.Refused(refusal)
            else -> apply(RotationOutcome(adapter, credentialId, state.current, now.plus(window)), actingUserId, now)
        }
    }

    private fun refusalFor(
        state: AdapterCredentialState,
        credentialId: CredentialRef,
        now: Instant,
    ): RotationRefusal? =
        when {
            credentialId == state.current -> RotationRefusal.SAME_AS_CURRENT
            state.overlapOpen(now) -> RotationRefusal.ROTATION_IN_PROGRESS
            !vault.holds(credentialId) -> RotationRefusal.UNKNOWN_REFERENCE
            else -> null
        }

    private fun apply(
        outcome: RotationOutcome,
        actingUserId: String,
        now: Instant,
    ): RotateResult {
        states.save(outcome.adapter, AdapterCredentialState(outcome.credentialId, outcome.previous, outcome.overlapEnd))
        log.record(CredentialRotationAudit(actingUserId, outcome, now))
        return RotateResult.Rotated(outcome, repeat = false)
    }
}

/** The previous reference is past its overlap end (or was never the adapter's): it is refused. */
class CredentialRefused(
    ref: CredentialRef,
) : IllegalStateException("credential ${ref.value} is not usable")

/** The credential of one AP call, fixed when the call starts. */
class CallCredential internal constructor(
    val adapter: AdapterId,
    val ref: CredentialRef,
)

/** Which credential an AP call uses, and the secret read for it at call time. */
class CredentialInUse(
    private val states: AdapterCredentialStates,
    private val vault: CredentialVault,
    private val clock: Clock,
) {
    /** A call started now uses the current reference, and keeps it until it finishes. */
    fun begin(adapter: AdapterId): CallCredential =
        CallCredential(adapter, (states.load(adapter) ?: throw AdapterNotRegistered(adapter.value)).current)

    /** Reads the secret of [call] from the vault by reference; refuses a previous reference after the overlap end. */
    fun secretFor(call: CallCredential): Secret {
        val state = states.load(call.adapter) ?: throw AdapterNotRegistered(call.adapter.value)
        val now = clock.instant()
        val expired = state.previous != null && !state.overlapOpen(now)
        if (expired) states.save(call.adapter, AdapterCredentialState(state.current))
        val usable = call.ref == state.current || (call.ref == state.previous && state.overlapOpen(now))
        return (if (usable) vault.read(call.ref) else null) ?: throw CredentialRefused(call.ref)
    }
}
