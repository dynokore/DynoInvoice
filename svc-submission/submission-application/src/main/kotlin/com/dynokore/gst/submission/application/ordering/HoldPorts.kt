package com.dynokore.gst.submission.application.ordering

import com.dynokore.platform.tenancykernel.TenantId

/** A version held in `AWAITING_PRECEDING` with the register facts of its document (INV-REQ-709). */
data class HeldVersion(
    val docUuid: String,
    val version: Int,
    val precedingDocUuid: String,
    val entityRef: String,
    val periodRef: String,
)

/** The store that holds and releases a version, and lists the held ones (INV-REQ-709). Every call names the tenant. */
interface HoldStore {
    /** READY to AWAITING_PRECEDING; the row count: 1 moved, 0 when the version is not READY. */
    fun holdReady(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): Int

    /** AWAITING_PRECEDING to READY; the row count: 1 moved, 0 when the version is not held. */
    fun releaseHeld(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): Int

    /** The held versions whose document names [precedingDocUuid], by (doc_uuid, version). */
    fun heldBy(
        tenant: TenantId,
        precedingDocUuid: String,
    ): List<HeldVersion>

    /** At most [limit] held versions of the tenant, by (doc_uuid, version); a [limit] below 1 throws. */
    fun held(
        tenant: TenantId,
        limit: Int,
    ): List<HeldVersion>
}

/** The tenants that hold any version, read outside any tenant transaction. */
fun interface HeldTenantSource {
    fun tenants(): List<TenantId>
}
