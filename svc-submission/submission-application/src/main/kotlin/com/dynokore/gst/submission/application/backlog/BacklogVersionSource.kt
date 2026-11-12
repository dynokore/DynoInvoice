package com.dynokore.gst.submission.application.backlog

import com.dynokore.gst.submission.domain.metrics.BacklogVersion
import com.dynokore.platform.tenancykernel.TenantId

/**
 * What one read of the backlog source returns: the versions, and the number of version rows left out because they
 * cannot form a [BacklogVersion] (no register entry, no due date, or a pending version with no queued instant and no
 * attempt row). The count lets the caller log it; it is never part of an event and no value is invented (INV-REQ-1370).
 */
data class BacklogRead(
    val versions: List<BacklogVersion>,
    val skipped: Int,
)

/** The read port of the backlog values: every version row of one tenant. */
interface BacklogVersionSource {
    fun versions(tenant: TenantId): BacklogRead
}
