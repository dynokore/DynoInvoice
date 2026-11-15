package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.adapters.elevation.CommandKey
import com.dynokore.gst.access.adapters.elevation.ElevationCommands
import com.dynokore.gst.access.adapters.elevation.ElevationCommands.Reply
import com.dynokore.gst.access.adapters.persistence.AuthorityIncarnationStore
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.contract.model.AuthorityIncarnationResponse
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * `bumpAuthorityIncarnation` (INV-REQ-1201, INV-Q-9243 B): raises the stored incarnation to
 * `max(stored + 1, now in epoch seconds)` through [AuthorityIncarnationStore.bump] and answers the new and the previous
 * value. The caller is checked by the permission key only ([BumpPermissionCheck]); any other caller is 403 with the
 * stored value unchanged. The command and its Idempotency-Key record commit in one transaction (API-15,
 * [ElevationCommands.once]): a replay answers the first answer and raises the value once. The audit entry names the
 * acting user ID, never a role (INV-Q-10647). It is served by the controller of the tag `authorise`, which implements
 * the generated interface.
 */
@Component
class AuthorityIncarnationOperation(
    private val store: AuthorityIncarnationStore,
    private val permission: BumpPermissionCheck,
    private val commands: ElevationCommands,
    private val tenants: TenantContext,
    private val audit: GrantAudit,
) {
    fun bump(idempotencyKey: UUID): ResponseEntity<AuthorityIncarnationResponse> {
        val actor = tenants.currentActor() ?: throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        if (!permission.mayBump(actor)) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        val id = CommandKey(IdentityRef(actor), idempotencyKey, "POST bumpAuthorityIncarnation")
        return commands.once(id, null, commands.reader(AuthorityIncarnationResponse::class.java)) {
            val previous = store.current()
            val raised = store.bump()
            audit.record(
                GrantAuditEntry(
                    actor = actor,
                    action = "bumpAuthorityIncarnation",
                    scope = "installation",
                    reason = "previousIncarnation=$previous incarnation=$raised",
                    priorStateJson = null,
                ),
            )
            Reply(HttpStatus.OK, AuthorityIncarnationResponse(raised, previous))
        }
    }
}
