package com.dynokore.gst.consoleBff.application.profile

/** Answer of one owning-service setPolicy call; a refusal is a value, a thrown exception is also a failure. */
sealed interface PolicyWriteResult {
    data object Success : PolicyWriteResult

    data class Rejected(
        val reason: String,
    ) : PolicyWriteResult
}

/** Writes one policy value with source PROFILE; effectiveDate and reason are adapter concerns. */
fun interface PolicyWriter {
    @Suppress("LongParameterList") // port shape fixed by the task text: one argument per written value
    fun set(
        tenantId: String,
        userId: String,
        entityRef: String,
        scope: ProfileScope,
        variationPoint: String,
        option: String,
        profileRef: String,
        profileVersion: Int,
    ): PolicyWriteResult
}

/** Records the applied profile (tenancy recordAppliedOperatingProfile) after every write succeeded. */
fun interface AppliedProfileRecorder {
    fun record(
        tenantId: String,
        scope: ProfileScope,
        entityRef: String,
        profileRef: String,
        profileVersion: Int,
    ): PolicyWriteResult
}
