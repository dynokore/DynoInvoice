package com.dynokore.gst.controlPlane.application.audit

import com.dynokore.gst.controlPlane.application.pack.RequestContext
import java.time.Instant
import java.time.Period

/** The permission an audit purge needs, and the scope it is asked in (never a role name, INV-Q-10027). */
object AuditPurgePermission {
    const val KEY = "audit-entry.purge"
    const val SCOPE = "platform"
}

/**
 * The longest VP-43 evidence retention of each data region (INV-Q-3263 a). A region that is unreachable or gives no
 * figure is a null entry; the call may also throw, which counts as undeterminable.
 */
fun interface AuditPurgeFloorSource {
    fun regionRetentions(): List<Period?>
}

/** Decides a permission per request. Throws on an error or timeout; the use case then denies. */
fun interface AuditPurgeAuthority {
    fun allows(
        actor: String,
        permission: String,
        scope: String,
    ): Boolean
}

/** What a purge deleted, and the audit entry it recorded in the same transaction. */
data class AuditPurgeResult(
    val deletedCount: Int,
    val auditEntryId: String,
)

/** Performs the delete of the entries at or before [cutOff]. */
fun interface AuditEntryPurger {
    fun purge(
        actor: String,
        cutOff: Instant,
        reason: String,
        context: RequestContext,
    ): AuditPurgeResult
}
