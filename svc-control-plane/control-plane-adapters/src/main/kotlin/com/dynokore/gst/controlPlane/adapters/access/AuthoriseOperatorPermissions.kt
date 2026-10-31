package com.dynokore.gst.controlPlane.adapters.access

import com.dynokore.gst.controlPlane.application.release.OperatorPermissions

/**
 * The [OperatorPermissions] port over the live access-svc `authorise` query (INV-Q-13897 A): a key is held only if
 * `authorise` answers HTTP 200 and `decision` ALLOW for the actor, the key and the scope `platform`. The `role` field
 * is not read. Every failure gives false (see [AuthoriseClient]).
 */
class AuthoriseOperatorPermissions(
    private val authorise: AuthoriseClient,
) : OperatorPermissions {
    override fun holds(
        actor: String,
        permissionKey: String,
    ): Boolean = authorise.ask(actor, permissionKey)?.allow == true
}
