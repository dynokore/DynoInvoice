package com.dynokore.gst.access.adapters.approval

import com.dynokore.gst.access.application.approval.ApprovalPermissions
import com.dynokore.gst.access.application.authorise.Authorise
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.separation.SoleOperatorAcks
import com.dynokore.gst.access.domain.grant.Decision
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Target
import com.dynokore.platform.configresolver.PolicyChange
import com.dynokore.platform.configresolver.PolicyRefusal
import com.dynokore.platform.configresolver.PolicyRefused
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.configresolver.SetPolicyCommand
import com.dynokore.platform.configresolver.VariationPointKey
import java.util.function.Supplier

/**
 * A change of VP-03 or VP-32 by a person who also holds the permission `validation.override` on the entity needs the
 * tenant's ACTIVE sole-operator acknowledgement, and its audit entry records the sole-operator fact and the
 * acknowledgement (INV-REQ-1293 behaviour 2, INV-REQ-860 behaviour 4, INV-AC-2042). The check cannot be a
 * `ValueConstraints` verdict, which the platform writer asks only for values that need the owning service's check,
 * so this service, which owns both rows, decides here before the delegate writes and audits in the same transaction
 * after it (the servlet runs `setPolicy` in one). The permission is decided from the live grants (INV-Q-10027), never
 * from a role name; a failed decision is not turned into an answer. Every other command passes to [delegate] unchanged.
 */
class ApprovalPolicyWriter(
    val delegate: PolicyWriter,
    private val authorise: Supplier<Authorise>,
    private val acks: Supplier<SoleOperatorAcks>,
    private val audit: Supplier<GrantAudit>,
) : PolicyWriter by delegate {
    override fun setPolicy(command: SetPolicyCommand): PolicyChange {
        val entity = command.scope.entity?.takeIf { command.key in KEYS }
        val holdsOverride =
            entity != null &&
                authorise.get().handle(
                    IdentityRef(command.actor.principal),
                    ApprovalPermissions.OVERRIDE,
                    Target(EntityRef(entity)),
                ) is Decision.Allow
        val ack = if (holdsOverride) acks.get().findActive() else null
        if (holdsOverride && ack == null) {
            throw PolicyRefused(
                PolicyRefusal.CONSTRAINT_REFUSED,
                command.key,
                "a person who also holds validation.override needs the recorded sole-operator acknowledgement",
            )
        }
        val change = delegate.setPolicy(command)
        if (ack != null) {
            audit.get().record(
                GrantAuditEntry(
                    actor = command.actor.principal,
                    action = "setPolicy.soleOperator",
                    scope = "${command.level}:$entity",
                    reason = "variationPoint=${command.key.value} soleOperator=true soleOperatorAckRef=${ack.ref}",
                    priorStateJson = null,
                ),
            )
        }
        return change
    }

    private companion object {
        val KEYS = setOf(VariationPointKey("VP-03"), VariationPointKey("VP-32"))
    }
}
