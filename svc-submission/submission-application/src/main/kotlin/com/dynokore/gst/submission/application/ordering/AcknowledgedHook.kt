package com.dynokore.gst.submission.application.ordering

import com.dynokore.platform.tenancykernel.TenantId

/**
 * Called by the acknowledgement consumer, in its transaction, after a version of [docUuid] was recorded as
 * ACKNOWLEDGED (INV-REQ-709). An exception propagates and rolls the consumer transaction back.
 */
fun interface AcknowledgedHook {
    fun acknowledged(
        tenant: TenantId,
        docUuid: String,
    )
}
