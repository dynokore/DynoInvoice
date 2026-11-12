package com.dynokore.gst.rules.domain.pack

/** The commands that move a regulatory pack along its lifecycle (INV-REQ-489). */
enum class RegulatoryCommand {
    APPROVE,
    ARM,
}

/**
 * The approval step of a regulatory pack (INV-REQ-489, INV-AC-20459 domain part). The table is closed and holds exactly
 * one transition, (STAGED, APPROVE) to APPROVED; every other pair is a typed refusal that carries no error code.
 * ARMED and ACKNOWLEDGED handling beyond [ArmingGate] belongs to INV-T-4977.
 */
object RegulatoryPackLifecycle {
    sealed interface Transition {
        data class To(
            val status: PackStatus,
        ) : Transition

        /** The command is not allowed in [status]. */
        data class Refused(
            val status: PackStatus,
            val command: RegulatoryCommand,
        ) : Transition
    }

    private val ALLOWED: Map<Pair<PackStatus, RegulatoryCommand>, PackStatus> =
        mapOf((PackStatus.STAGED to RegulatoryCommand.APPROVE) to PackStatus.APPROVED)

    fun next(
        status: PackStatus,
        command: RegulatoryCommand,
    ): Transition = ALLOWED[status to command]?.let { Transition.To(it) } ?: Transition.Refused(status, command)
}

/** The arming check that runs before the activation fence (INV-REQ-1166); refusals are typed values, never error codes. */
object ArmingGate {
    enum class Reason {
        NO_DRY_RUN,
        NOT_APPROVED,
    }

    sealed interface Outcome {
        data class Refused(
            val reason: Reason,
        ) : Outcome

        /** The pack is approved; the activation fence decides next. */
        data object PassedToFence : Outcome
    }

    fun check(
        status: PackStatus,
        dryRunExists: Boolean,
    ): Outcome =
        when {
            status == PackStatus.APPROVED -> Outcome.PassedToFence
            status == PackStatus.STAGED && !dryRunExists -> Outcome.Refused(Reason.NO_DRY_RUN)
            else -> Outcome.Refused(Reason.NOT_APPROVED)
        }
}
