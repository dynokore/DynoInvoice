package com.dynokore.gst.consoleBff.application.profile

/** Writes the profile values that differ, then records the profile; fails closed on any port failure. */
class ProfileApplierImpl(
    private val facts: ConditionFacts,
    private val currentValues: CurrentValues,
    private val writer: PolicyWriter,
    private val recorder: AppliedProfileRecorder,
) : ProfileApplier {
    override fun apply(command: ProfileApplyCommand): ProfileApplyResult =
        try {
            applyChecked(command)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            ProfileApplyResult.Failed("port failure: ${e.javaClass.simpleName}")
        }

    private fun applyChecked(command: ProfileApplyCommand): ProfileApplyResult {
        val profile = command.profile
        return when {
            profile.status != ProfileStatus.AVAILABLE -> {
                ProfileApplyResult.Failed("profile is not available")
            }

            else -> {
                when (val resolved = resolve(command)) {
                    is Resolved.Undecided -> ProfileApplyResult.Failed("undecided: ${resolved.variationPoint}")
                    is Resolved.Pending -> writeAll(command, resolved.changes)
                }
            }
        }
    }

    private sealed interface Resolved {
        data class Pending(
            val changes: List<ProfileValueChange>,
        ) : Resolved

        data class Undecided(
            val variationPoint: String,
        ) : Resolved
    }

    private fun resolve(command: ProfileApplyCommand): Resolved {
        val pending = mutableListOf<ProfileValueChange>()
        for (value in command.profile.values) {
            val proposed = proposedOption(command, value) ?: return Resolved.Undecided(value.variationPoint)
            val current = currentValues.current(command.tenantId, command.entityRef, value.variationPoint)
            if (current != proposed) pending += ProfileValueChange(value.variationPoint, current, proposed)
        }
        return Resolved.Pending(pending)
    }

    private fun writeAll(
        command: ProfileApplyCommand,
        pending: List<ProfileValueChange>,
    ): ProfileApplyResult {
        val profile = command.profile
        val written = mutableListOf<ProfileValueChange>()
        var failure: String? = null
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
                failure = "write failed at ${change.variationPoint}: ${result.reason}"
                break
            }
            written += change
        }
        if (failure == null) {
            val recorded =
                recorder.record(command.tenantId, command.scope, command.entityRef, profile.profileRef, profile.version)
            if (recorded is PolicyWriteResult.Rejected) failure = "record failed: ${recorded.reason}"
        }
        return failure?.let { ProfileApplyResult.Failed("$it; written: ${names(written)}") }
            ?: ProfileApplyResult.Applied(written)
    }

    private fun names(changes: List<ProfileValueChange>) = changes.joinToString(",") { it.variationPoint }

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
