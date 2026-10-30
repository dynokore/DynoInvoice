package com.dynokore.gst.submission.domain.transitions

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.ordering.LifecycleState.ACKNOWLEDGED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.DEAD_LETTERED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.NETWORK_ISSUED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.QUEUED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.REJECTED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.RETRY
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SENT
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SENT_UNCONFIRMED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SUPERSEDED
import java.time.Instant

/** One version of a Doc UUID as the caller read it; [acknowledgementId] is the one already recorded, if any. */
data class VersionRecord(
    val number: Int,
    val state: LifecycleState,
    val acknowledgementId: String? = null,
) {
    init {
        require(number >= 1) { "version number must be at least 1: $number" }
    }
}

enum class RejectionSource { AP, IRAS }

data class RejectionReason(
    val code: String,
    val field: String? = null,
    val message: String,
)

sealed interface Event {
    val version: Int

    data class ApAccepted(
        override val version: Int,
    ) : Event

    data class AcknowledgementReceived(
        override val version: Int,
        val acknowledgementId: String,
    ) : Event

    data class Rejected(
        override val version: Int,
        val source: RejectionSource,
        val reasons: List<RejectionReason>,
    ) : Event

    data class TimerTick(
        override val version: Int,
        val now: Instant,
        val t1At: Instant,
        val t2At: Instant,
        val latestDespatchAt: Instant,
        val dueAt: Instant,
        val resendsDone: Int,
        val sub005Raised: Boolean,
        val sub010Raised: Boolean,
    ) : Event {
        init {
            require(resendsDone >= 0) { "resendsDone must be at least 0: $resendsDone" }
        }
    }
}

enum class RaisedCode(
    val code: String,
) {
    SUB_003("SUB-003"),
    SUB_004("SUB-004"),
    SUB_005("SUB-005"),
    SUB_010("SUB-010"),
}

enum class RefusalReason { NO_ACKNOWLEDGEMENT_ID }

enum class AnomalyReason { UNKNOWN_VERSION, AP_ACCEPT_IN_STATE, ACK_IN_STATE, ACK_ID_DIFFERS, REJECT_IN_STATE }

sealed interface Signal {
    data class StartAckTimer(
        val version: Int,
    ) : Signal

    data class RecordAcknowledgement(
        val version: Int,
        val acknowledgementId: String,
    ) : Signal

    data class Resend(
        val version: Int,
    ) : Signal

    data class Raise(
        val code: RaisedCode,
        val version: Int,
    ) : Signal

    data class Refused(
        val reason: RefusalReason,
    ) : Signal

    data class Anomaly(
        val reason: AnomalyReason,
    ) : Signal
}

data class Transition(
    val version: Int,
    val from: LifecycleState,
    val to: LifecycleState,
)

/** [reasons] are the rejection reasons of a `Rejected` event, unchanged and in order; empty for every other event. */
data class Decision(
    val transitions: List<Transition> = emptyList(),
    val signals: List<Signal> = emptyList(),
    val reasons: List<RejectionReason> = emptyList(),
)

/**
 * INV-REQ-797, INV-REQ-630, INV-REQ-543, INV-REQ-574 (INV-Q-6337, option A): a version's reaction to an AP success, an
 * acknowledgement, a rejection and the timer ticks. Only an acknowledgement with a non-blank id moves a version to
 * ACKNOWLEDGED. Pure: no state, no clock; equal input gives equal output.
 */
object VersionTransitions {
    private val ACK_ACCEPTING = setOf(SENT, SENT_UNCONFIRMED, NETWORK_ISSUED, QUEUED, RETRY, DEAD_LETTERED)
    private val REJECTABLE = setOf(QUEUED, SENT, SENT_UNCONFIRMED, RETRY)

    fun decide(
        versions: List<VersionRecord>,
        event: Event,
    ): Decision {
        val target =
            versions.firstOrNull { it.number == event.version } ?: return anomaly(AnomalyReason.UNKNOWN_VERSION)
        return when (event) {
            is Event.ApAccepted -> apAccepted(target)
            is Event.AcknowledgementReceived -> acknowledged(versions, target, event)
            is Event.Rejected -> rejected(target, event)
            is Event.TimerTick -> tick(target, event)
        }
    }

    private fun anomaly(reason: AnomalyReason) = Decision(signals = listOf(Signal.Anomaly(reason)))

    private fun apAccepted(target: VersionRecord): Decision =
        when (target.state) {
            QUEUED, RETRY -> {
                Decision(
                    listOf(Transition(target.number, target.state, SENT)),
                    listOf(Signal.StartAckTimer(target.number)),
                )
            }

            SENT -> {
                Decision()
            }

            else -> {
                anomaly(AnomalyReason.AP_ACCEPT_IN_STATE)
            }
        }

    private fun acknowledged(
        versions: List<VersionRecord>,
        target: VersionRecord,
        event: Event.AcknowledgementReceived,
    ): Decision {
        if (event.acknowledgementId.isBlank()) {
            return Decision(signals = listOf(Signal.Refused(RefusalReason.NO_ACKNOWLEDGEMENT_ID)))
        }
        val record = Signal.RecordAcknowledgement(target.number, event.acknowledgementId)
        return when {
            target.state == ACKNOWLEDGED -> {
                if (target.acknowledgementId == event.acknowledgementId) {
                    Decision()
                } else {
                    anomaly(AnomalyReason.ACK_ID_DIFFERS)
                }
            }

            target.state !in ACK_ACCEPTING -> {
                anomaly(AnomalyReason.ACK_IN_STATE)
            }

            versions.any { it.number > target.number && it.state == ACKNOWLEDGED } -> {
                Decision(listOf(Transition(target.number, target.state, SUPERSEDED)), listOf(record))
            }

            else -> {
                val older =
                    versions
                        .filter { it.number < target.number && it.state == ACKNOWLEDGED }
                        .map { Transition(it.number, ACKNOWLEDGED, SUPERSEDED) }
                Decision(listOf(Transition(target.number, target.state, ACKNOWLEDGED)) + older, listOf(record))
            }
        }
    }

    private fun rejected(
        target: VersionRecord,
        event: Event.Rejected,
    ): Decision {
        if (target.state !in REJECTABLE) return anomaly(AnomalyReason.REJECT_IN_STATE)
        val code = if (event.source == RejectionSource.IRAS) RaisedCode.SUB_004 else RaisedCode.SUB_003
        return Decision(
            listOf(Transition(target.number, target.state, REJECTED)),
            listOf(Signal.Raise(code, target.number)),
            event.reasons,
        )
    }

    private fun tick(
        target: VersionRecord,
        tick: Event.TimerTick,
    ): Decision {
        if (target.state != SENT && target.state != SENT_UNCONFIRMED) return Decision()
        val effective2 = minOf(tick.t2At, tick.latestDespatchAt)
        val effective1 = minOf(tick.t1At, effective2)
        val transitions = mutableListOf<Transition>()
        val signals = mutableListOf<Signal>()
        if (!tick.now.isBefore(effective1) && tick.resendsDone == 0 && target.state == SENT) {
            transitions += Transition(target.number, SENT, SENT_UNCONFIRMED)
            signals += Signal.Resend(target.number)
        }
        if (!tick.now.isBefore(effective2) && !tick.sub005Raised) {
            signals += Signal.Raise(RaisedCode.SUB_005, target.number)
        }
        if (tick.now.isAfter(tick.dueAt) && !tick.sub010Raised) {
            signals += Signal.Raise(RaisedCode.SUB_010, target.number)
        }
        return Decision(transitions, signals)
    }
}
