package com.dynokore.gst.evidence.application.archive

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant

/**
 * The bytes of retained content one entity holds in the database store (INV-Q-12683 A). Non-negative; an adapter that
 * finds a negative sum raises [IllegalStateException].
 */
fun interface StoredContentBytes {
    fun bytes(
        tenant: TenantId,
        entityRef: String,
    ): Long
}

/**
 * The bytes of retained content one entity holds in the object store (INV-Q-12683 A). Non-negative; an adapter that
 * finds a negative sum raises [IllegalStateException].
 */
fun interface ObjectBytes {
    fun bytes(
        tenant: TenantId,
        entityRef: String,
    ): Long
}

/** The retained archive volume of one entity: the database-store sum plus the object-store sum. Non-negative. */
fun interface ArchiveVolumeSource {
    fun bytesRetained(
        tenant: TenantId,
        entityRef: String,
    ): Long
}

/** The last published volume of each entity. Every method runs in the caller's tenant transaction. */
interface ArchiveVolumeLedger {
    /** The last recorded bytes of (tenant, entity), or `null` when none was recorded. */
    fun last(
        tenant: TenantId,
        entityRef: String,
    ): Long?

    fun record(
        tenant: TenantId,
        entityRef: String,
        bytes: Long,
        measuredAt: Instant,
    )
}
