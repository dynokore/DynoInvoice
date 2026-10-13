package com.dynokore.platform.rulepackkernel.fence

import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The single effect that arms the pack: `rules-svc` setting `effective_from`.
 * One write, so it either happens or does not.
 */
fun interface PackArming {
    fun arm(
        packId: PackId,
        version: PackVersionLabel,
    )
}

sealed interface FenceOutcome {
    /** Every expected instance acknowledged `Loaded` and the pack was armed. */
    data object Armed : FenceOutcome

    /** Acknowledgements are still missing and the window is open. Nothing is armed. */
    data class Pending(
        val missing: Set<String>,
    ) : FenceOutcome

    /**
     * The activation is abandoned and nothing is armed (INV-REQ-1166 behaviour #4): report [ERROR_CODE],
     * a person re-arms once the cause is fixed. [failed] instances answered `Failed`; [missing] never answered
     * within the window; [armingFailed] means the arming write itself failed.
     */
    data class Aborted(
        val failed: Map<String, ReadinessOutcome.Failed>,
        val missing: Set<String>,
        val armingFailed: Boolean = false,
    ) : FenceOutcome {
        val errorCode: String get() = ERROR_CODE
    }

    companion object {
        /** RUL-005: BLOCK, MANUAL, owner Platform (INV-Q-2132). */
        const val ERROR_CODE = "RUL-005"
    }
}

/**
 * The coordinator side of the two-phase fence for one staged pack (INV-REQ-1166, INV-REQ-1009), run by
 * `rules-svc`. [expected] is every instance under the fence: `rules-svc` and the pack-holding services
 * present in the train. [record] takes each `PackReadinessAcknowledged`; [evaluate] decides.
 *
 *  - all expected acknowledged `Loaded`: [PackArming.arm] once, outcome [FenceOutcome.Armed];
 *  - any `Failed`, or any missing once the [window] has elapsed: [FenceOutcome.Aborted], [PackArming.arm]
 *    is never called, so no instance arms;
 *  - otherwise [FenceOutcome.Pending].
 *
 * The outcome is final once Armed or Aborted; later calls return it unchanged. Acknowledgements for another
 * pack or version, or from an instance that is not expected, are ignored; for one instance the latest wins
 * (a re-staged pack may fix an earlier failure). Not thread-safe by itself: callers serialise on the fence.
 */
class ActivationFence(
    val packId: PackId,
    val version: PackVersionLabel,
    private val expected: Set<String>,
    private val window: Duration,
    private val clock: Clock,
    private val arming: PackArming,
) {
    private val started: Instant = clock.instant()
    private val answers = mutableMapOf<String, ReadinessOutcome>()
    private var final: FenceOutcome? = null

    init {
        require(expected.isNotEmpty()) { "a fence needs at least one expected instance" }
        require(!window.isNegative && !window.isZero) { "window must be positive, was $window" }
    }

    @Synchronized
    fun record(acknowledgement: PackReadinessAcknowledgement) {
        if (final != null) return
        val relevant = acknowledgement.packId == packId && acknowledgement.version == version
        if (relevant && acknowledgement.instance in expected) {
            answers[acknowledgement.instance] = acknowledgement.outcome
        }
    }

    @Synchronized
    fun evaluate(): FenceOutcome {
        val decided = final ?: decide()
        if (decided !is FenceOutcome.Pending) final = decided
        return decided
    }

    private fun decide(): FenceOutcome {
        val failed = mutableMapOf<String, ReadinessOutcome.Failed>()
        answers.forEach { (instance, outcome) -> if (outcome is ReadinessOutcome.Failed) failed[instance] = outcome }
        val missing = expected - answers.keys
        return when {
            failed.isNotEmpty() -> FenceOutcome.Aborted(failed, missing)
            missing.isEmpty() -> armNow()
            clock.instant() >= started.plus(window) -> FenceOutcome.Aborted(emptyMap(), missing)
            else -> FenceOutcome.Pending(missing)
        }
    }

    private fun armNow(): FenceOutcome =
        try {
            arming.arm(packId, version)
            FenceOutcome.Armed
        } catch (
            @Suppress("TooGenericExceptionCaught") ignored: RuntimeException,
        ) {
            FenceOutcome.Aborted(emptyMap(), emptySet(), armingFailed = true)
        }
}
