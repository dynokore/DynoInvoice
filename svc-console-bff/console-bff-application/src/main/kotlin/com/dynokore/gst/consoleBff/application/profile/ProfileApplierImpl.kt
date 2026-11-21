package com.dynokore.gst.consoleBff.application.profile

/** Writes the profile values that differ, then records the profile; fails closed on any port failure. */
class ProfileApplierImpl(
    private val facts: ConditionFacts,
    private val currentValues: CurrentValues,
    private val writer: PolicyWriter,
    private val recorder: AppliedProfileRecorder,
) : ProfileApplier {
    override fun apply(command: ProfileApplyCommand): ProfileApplyResult {
        val written = mutableListOf<ProfileValueChange>()
        return try {
            if (command.profile.status == ProfileStatus.AVAILABLE) {
                applyAvailable(command, written)
            } else {
                ProfileApplyResult.Failed("profile is not available")
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            ProfileApplyResult.Failed("port failure: ${e.javaClass.simpleName}", written.toList())
        }
    }

    private fun applyAvailable(
        command: ProfileApplyCommand,
        written: MutableList<ProfileValueChange>,
    ): ProfileApplyResult =
        when (val resolved = resolve(command)) {
            is Resolved.Undecided -> ProfileApplyResult.ConditionUndecided(resolved.variationPoint)
            is Resolved.NeedsAcknowledgement -> ProfileApplyResult.AcknowledgementRequired(resolved.variationPoints)
            is Resolved.Pending -> writeAll(command, resolved.changes, written)
        }

    private sealed interface Resolved {
        data class Pending(
            val changes: List<ProfileValueChange>,
        ) : Resolved

        data class Undecided(
            val variationPoint: String,
        ) : Resolved

        data class NeedsAcknowledgement(
            val variationPoints: List<String>,
        ) : Resolved
    }

    /** Resolves every value first; the acknowledgement check needs the resolved option. */
    private fun resolve(command: ProfileApplyCommand): Resolved {
        val pending = mutableListOf<ProfileValueChange>()
        val acknowledge = mutableListOf<String>()
        for (value in command.profile.values) {
            val proposed = proposedOption(command, value) ?: return Resolved.Undecided(value.variationPoint)
            if (proposed.split(",").any { it in value.needsAcknowledgement }) {
                acknowledge += value.variationPoint
            }
            val current = currentValues.current(command.tenantId, command.entityRef, value.variationPoint)
            if (current != proposed) pending += ProfileValueChange(value.variationPoint, current, proposed)
        }
        return if (acknowledge.isEmpty()) Resolved.Pending(pending) else Resolved.NeedsAcknowledgement(acknowledge)
    }

    private fun writeAll(
        command: ProfileApplyCommand,
        pending: List<ProfileValueChange>,
        written: MutableList<ProfileValueChange>,
    ): ProfileApplyResult {
        val profile = command.profile
        for (change in pending) {
            val result =
                writer.set(
                    command.tenantId,
                    command.userId,
                    command.entityRef,
                    command.scope,
                    change.variationPoint,
                    change.proposed,
                    profile.profileRef,
                    profile.version,
                )
            if (result is PolicyWriteResult.Rejected) {
                return ProfileApplyResult.Failed(
                    "write failed at ${change.variationPoint}: ${result.reason}",
                    written.toList(),
                )
            }
            written += change
        }
        val recorded =
            recorder.record(command.tenantId, command.scope, command.entityRef, profile.profileRef, profile.version)
        return if (recorded is PolicyWriteResult.Rejected) {
            ProfileApplyResult.Failed("record failed: ${recorded.reason}", written.toList())
        } else {
            ProfileApplyResult.Applied(written.toList())
        }
    }

    /** Null when the condition is not decidable or no otherwise option exists: no guess. */
    private fun proposedOption(
        command: ProfileApplyCommand,
        value: ProfileValue,
    ): String? {
        val constraint = value.conditionConstraint
        val chosen = constraint?.let { facts.holds(command.tenantId, command.entityRef, it) }
        return when {
            constraint == null || chosen == true -> value.options.joinToString(",")
            chosen == false -> value.otherwiseOption
            else -> null
        }
    }
}
