package com.dynokore.gst.consoleBff.application.profile

data class ApplyOperatingProfileRequest(
    val mode: ProfileMode,
    val scope: ProfileScope,
    val profileRef: String,
    val profileVersion: Int,
)

data class ProfileValueChange(
    val variationPoint: String,
    val current: String?,
    val proposed: String,
)

sealed interface ApplyOperatingProfileOutcome {
    data class Previewed(
        val profileRef: String,
        val profileVersion: Int,
        val status: ProfileStatus,
        val changes: List<ProfileValueChange>,
    ) : ApplyOperatingProfileOutcome

    data class Applied(
        val profileRef: String,
        val profileVersion: Int,
        val changes: List<ProfileValueChange>,
    ) : ApplyOperatingProfileOutcome

    data class NotAvailable(
        val status: ProfileStatus,
    ) : ApplyOperatingProfileOutcome

    data class ConditionUndecided(
        val variationPoint: String,
    ) : ApplyOperatingProfileOutcome

    data class AcknowledgementRequired(
        val variationPoints: List<String>,
    ) : ApplyOperatingProfileOutcome

    data class Invalid(
        val reason: String,
    ) : ApplyOperatingProfileOutcome

    data object Forbidden : ApplyOperatingProfileOutcome

    data object NotFound : ApplyOperatingProfileOutcome

    data class Unavailable(
        val reason: String,
    ) : ApplyOperatingProfileOutcome
}

class ApplyOperatingProfile(
    private val authority: ProfileAuthority,
    private val catalogue: ProfileCatalogue,
    private val facts: ConditionFacts,
    private val currentValues: CurrentValues,
    private val applier: ProfileApplier,
) {
    fun execute(
        tenantId: String,
        userId: String,
        entityRef: String,
        request: ApplyOperatingProfileRequest,
    ): ApplyOperatingProfileOutcome =
        when {
            request.profileRef.isBlank() -> ApplyOperatingProfileOutcome.Invalid("profileRef is blank")
            request.profileVersion < 1 -> ApplyOperatingProfileOutcome.Invalid("profileVersion is below 1")
            else -> guarded { run(tenantId, userId, entityRef, request) }
        }

    private fun guarded(block: () -> ApplyOperatingProfileOutcome): ApplyOperatingProfileOutcome =
        try {
            block()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            ApplyOperatingProfileOutcome.Unavailable("port failure: ${e.javaClass.simpleName}")
        }

    private fun run(
        tenantId: String,
        userId: String,
        entityRef: String,
        request: ApplyOperatingProfileRequest,
    ): ApplyOperatingProfileOutcome {
        val profile =
            if (authority.holds(tenantId, userId, entityRef, request.scope)) {
                catalogue.find(request.profileRef, request.profileVersion)
            } else {
                return ApplyOperatingProfileOutcome.Forbidden
            }
        return when {
            profile == null -> ApplyOperatingProfileOutcome.NotFound
            request.mode == ProfileMode.APPLY -> apply(tenantId, userId, entityRef, request, profile)
            profile.status == ProfileStatus.AVAILABLE -> previewAvailable(tenantId, entityRef, profile)
            else -> previewAll(tenantId, entityRef, profile)
        }
    }

    private fun previewAll(
        tenantId: String,
        entityRef: String,
        profile: ProfileDefinition,
    ): ApplyOperatingProfileOutcome {
        val changes =
            profile.values.map {
                ProfileValueChange(
                    it.variationPoint,
                    currentValues.current(tenantId, entityRef, it.variationPoint),
                    it.options.joinToString(","),
                )
            }
        return previewed(profile, changes)
    }

    private fun previewAvailable(
        tenantId: String,
        entityRef: String,
        profile: ProfileDefinition,
    ): ApplyOperatingProfileOutcome =
        when (val resolved = resolveChanges(tenantId, entityRef, profile)) {
            is Resolution.Undecided -> ApplyOperatingProfileOutcome.ConditionUndecided(resolved.variationPoint)
            is Resolution.Changes -> previewed(profile, resolved.changes)
        }

    /** The live task text (7): the result of the applier; the applier resolves the values itself. */
    private fun apply(
        tenantId: String,
        userId: String,
        entityRef: String,
        request: ApplyOperatingProfileRequest,
        profile: ProfileDefinition,
    ): ApplyOperatingProfileOutcome =
        if (profile.status != ProfileStatus.AVAILABLE) {
            ApplyOperatingProfileOutcome.NotAvailable(profile.status)
        } else {
            when (
                val result = applier.apply(ProfileApplyCommand(tenantId, userId, entityRef, request.scope, profile))
            ) {
                is ProfileApplyResult.Applied -> {
                    ApplyOperatingProfileOutcome.Applied(profile.profileRef, profile.version, result.changes)
                }

                is ProfileApplyResult.AcknowledgementRequired -> {
                    ApplyOperatingProfileOutcome.AcknowledgementRequired(result.variationPoints)
                }

                is ProfileApplyResult.ConditionUndecided -> {
                    ApplyOperatingProfileOutcome.ConditionUndecided(result.variationPoint)
                }

                is ProfileApplyResult.Failed -> {
                    ApplyOperatingProfileOutcome.Unavailable(result.reason)
                }
            }
        }

    private fun previewed(
        profile: ProfileDefinition,
        changes: List<ProfileValueChange>,
    ) = ApplyOperatingProfileOutcome.Previewed(profile.profileRef, profile.version, profile.status, changes)

    private sealed interface Resolution {
        data class Changes(
            val changes: List<ProfileValueChange>,
        ) : Resolution

        data class Undecided(
            val variationPoint: String,
        ) : Resolution
    }

    private fun resolveChanges(
        tenantId: String,
        entityRef: String,
        profile: ProfileDefinition,
    ): Resolution {
        val changes = mutableListOf<ProfileValueChange>()
        for (value in profile.values) {
            val proposed =
                proposedOption(tenantId, entityRef, value) ?: return Resolution.Undecided(value.variationPoint)
            val current = currentValues.current(tenantId, entityRef, value.variationPoint)
            if (current != proposed) changes += ProfileValueChange(value.variationPoint, current, proposed)
        }
        return Resolution.Changes(changes)
    }

    /** Null when the condition is not decidable or no otherwise option exists: no guess. */
    private fun proposedOption(
        tenantId: String,
        entityRef: String,
        value: ProfileValue,
    ): String? {
        val constraint = value.conditionConstraint
        val chosen = constraint?.let { facts.holds(tenantId, entityRef, it) }
        return when {
            constraint == null || chosen == true -> value.options.joinToString(",")
            chosen == false -> value.otherwiseOption
            else -> null
        }
    }
}
