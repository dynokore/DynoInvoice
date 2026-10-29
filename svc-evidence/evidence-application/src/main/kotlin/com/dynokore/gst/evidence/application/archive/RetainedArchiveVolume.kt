package com.dynokore.gst.evidence.application.archive

import com.dynokore.platform.tenancykernel.TenantId

/**
 * The retained archive volume of an entity: the database-store sum plus the object-store sum. Fails closed: an
 * exception of either port propagates and no value is returned, never one half alone. A negative half raises
 * [IllegalStateException]; a sum beyond `Long` raises [ArithmeticException].
 */
class RetainedArchiveVolume(
    private val stored: StoredContentBytes,
    private val objects: ObjectBytes,
) : ArchiveVolumeSource {
    override fun bytesRetained(
        tenant: TenantId,
        entityRef: String,
    ): Long {
        val inStore = stored.bytes(tenant, entityRef)
        check(inStore >= 0) { "the stored content bytes are negative" }
        val inObjects = objects.bytes(tenant, entityRef)
        check(inObjects >= 0) { "the object bytes are negative" }
        return Math.addExact(inStore, inObjects)
    }
}
