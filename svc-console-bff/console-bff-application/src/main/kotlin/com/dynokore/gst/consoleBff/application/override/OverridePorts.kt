package com.dynokore.gst.consoleBff.application.override

import java.time.Instant

/** The single scoped permission key of the override and of the routed reads. */
const val VALIDATION_OVERRIDE = "validation.override"

/** Decided per request by AuthorityReplica.decide in the adapter; no role is read. */
fun interface OverrideAuthority {
    fun holds(
        tenantId: String,
        principalId: String,
        entityRef: String,
    ): Boolean
}

data class OverridableRow(
    val exceptionId: String,
    val entityRef: String,
    val docUuid: String,
    val periodRef: String,
    val code: String,
    val state: String,
)

/** Read only; the tenant is the database binding. */
fun interface OverrideTargets {
    fun find(exceptionId: String): OverridableRow?
}

/** The members of the owning service's error envelope, relayed unchanged. */
data class OwnerRefusal(
    val status: Int,
    val code: String,
    val title: String,
    val severity: String?,
    val disposition: String?,
    val remediationOwner: String?,
    val detail: String?,
)

sealed interface OwnerReply<out T> {
    data class Ok<out T>(
        val value: T,
    ) : OwnerReply<T>

    data class Refused(
        val refusal: OwnerRefusal,
    ) : OwnerReply<Nothing>

    data class Unavailable(
        val reason: String,
    ) : OwnerReply<Nothing>
}

sealed interface Release {
    data class Released(
        val overriddenAt: Instant,
    ) : Release

    data class Pending(
        val requestedAt: Instant,
    ) : Release
}

interface PayloadOverrides {
    fun latestVersion(docUuid: String): OwnerReply<Long>

    fun override(
        docUuid: String,
        version: Long,
        code: String,
        reason: String,
    ): OwnerReply<Release>
}

interface SubmissionOverrides {
    fun latestCheck(
        entityRef: String,
        periodRef: String,
    ): OwnerReply<String>

    fun override(
        entityRef: String,
        periodRef: String,
        checkId: String,
        reason: String,
    ): OwnerReply<Release>
}
