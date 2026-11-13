package com.dynokore.gst.consoleBff.application.deferral

import com.dynokore.gst.consoleBff.domain.deferral.Deferral

/** The scoped permission key both deferral operations check. */
object DeferPermissions {
    const val EXCEPTION_DEFER = "exception.defer"
}

/** Decided per request by AuthorityReplica.decide in the adapter for the given scoped key; no role is read. */
fun interface DeferAuthority {
    fun holds(
        tenantId: String,
        principalId: String,
        entityRef: String,
        permission: String,
    ): Boolean
}

/** Existence of a target. The tenant is the database binding, as for DeferralStore. */
interface DeferralTargets {
    /** True when an exception row of this entity and doc_uuid is in state OPEN. */
    fun openDocument(
        entityRef: String,
        docUuid: String,
    ): Boolean

    /** True when [groupKey] ("owner/code") is a known group of this entity. */
    fun openGroup(
        entityRef: String,
        groupKey: String,
    ): Boolean
}

sealed interface DeferResult {
    data class Deferred(
        val deferral: Deferral,
    ) : DeferResult

    data object NotFound : DeferResult

    data object Forbidden : DeferResult
}

sealed interface UndeferResult {
    data class Undone(
        val deferral: Deferral,
    ) : UndeferResult

    data object NotFound : UndeferResult

    data object Forbidden : UndeferResult
}
