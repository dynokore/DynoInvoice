package com.dynokore.gst.rules.domain.pack

/** The commands that move a tenant mapping pack along its lifecycle (INV-REQ-380). */
enum class MappingCommand {
    SUBMIT,
    APPROVE,
    ACTIVATE,
}

/**
 * The lifecycle of a tenant mapping pack: DRAFT, SUBMITTED, APPROVED, ARMED (shown as ACTIVE in the contract)
 * (INV-REQ-380, INV-AC-1653 domain part). The table is closed: a pair that is not listed is a typed refusal that names
 * the status, and the four regulatory or terminal values never take a mapping command.
 */
object MappingPackLifecycle {
    /** The statuses a mapping pack passes through. */
    val MAPPING_STATUSES: Set<PackStatus> =
        setOf(PackStatus.DRAFT, PackStatus.SUBMITTED, PackStatus.APPROVED, PackStatus.ARMED)

    /** The statuses of other pack kinds and the terminal ones; a mapping command on any of them is refused. */
    val OTHER_STATUSES: Set<PackStatus> =
        setOf(PackStatus.STAGED, PackStatus.ACKNOWLEDGED, PackStatus.SUPERSEDED, PackStatus.ABORTED)

    sealed interface Transition {
        data class To(
            val status: PackStatus,
        ) : Transition

        /** The command is not allowed in [status]. */
        data class Refused(
            val status: PackStatus,
            val command: MappingCommand,
        ) : Transition
    }

    private val ALLOWED: Map<Pair<PackStatus, MappingCommand>, PackStatus> =
        mapOf(
            (PackStatus.DRAFT to MappingCommand.SUBMIT) to PackStatus.SUBMITTED,
            (PackStatus.SUBMITTED to MappingCommand.APPROVE) to PackStatus.APPROVED,
            (PackStatus.APPROVED to MappingCommand.ACTIVATE) to PackStatus.ARMED,
        )

    fun next(
        status: PackStatus,
        command: MappingCommand,
    ): Transition = ALLOWED[status to command]?.let { Transition.To(it) } ?: Transition.Refused(status, command)

    /** Entries may change only in DRAFT. */
    fun entriesMayChange(status: PackStatus): Boolean = status == PackStatus.DRAFT

    /** An acceptance may be recorded only in DRAFT or SUBMITTED. */
    fun acceptanceMayBeRecorded(status: PackStatus): Boolean =
        status == PackStatus.DRAFT || status == PackStatus.SUBMITTED
}
