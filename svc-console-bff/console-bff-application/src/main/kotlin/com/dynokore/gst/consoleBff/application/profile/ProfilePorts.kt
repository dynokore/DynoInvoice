package com.dynokore.gst.consoleBff.application.profile

enum class ProfileStatus { AVAILABLE, PLANNED, RETIRED }

enum class ProfileMode { PREVIEW, APPLY }

enum class ProfileScope { TENANT, ENTITY }

/** One variation-point value of a profile; plain types, the gst-catalogue types stay in a later adapter. */
data class ProfileValue(
    val variationPoint: String,
    val options: List<String>,
    val otherwiseOption: String? = null,
    val conditionConstraint: String? = null,
)

data class ProfileDefinition(
    val profileRef: String,
    val version: Int,
    val status: ProfileStatus,
    val values: List<ProfileValue>,
)

/** Decided per request by AuthorityReplica.decide in the adapter; no role is read. */
fun interface ProfileAuthority {
    fun holds(
        tenantId: String,
        userId: String,
        entityRef: String,
        scope: ProfileScope,
    ): Boolean
}

fun interface ProfileCatalogue {
    fun find(
        profileRef: String,
        version: Int,
    ): ProfileDefinition?
}

/** Answers null when the fact is not decidable. */
fun interface ConditionFacts {
    fun holds(
        tenantId: String,
        entityRef: String,
        constraint: String,
    ): Boolean?
}

/** Answers null when the source has no current value. */
fun interface CurrentValues {
    fun current(
        tenantId: String,
        entityRef: String,
        variationPoint: String,
    ): String?
}

data class ProfileApplyCommand(
    val tenantId: String,
    val userId: String,
    val entityRef: String,
    val scope: ProfileScope,
    val profile: ProfileDefinition,
    val changes: List<ProfileValueChange>,
)

sealed interface ProfileApplyResult {
    data class Applied(
        val changes: List<ProfileValueChange>,
    ) : ProfileApplyResult

    data class Failed(
        val reason: String,
    ) : ProfileApplyResult
}

/** Implemented by the applier slice; this task defines the port only. */
fun interface ProfileApplier {
    fun apply(command: ProfileApplyCommand): ProfileApplyResult
}
