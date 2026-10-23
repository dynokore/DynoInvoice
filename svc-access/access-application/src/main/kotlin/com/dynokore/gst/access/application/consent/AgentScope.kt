package com.dynokore.gst.access.application.consent

import com.dynokore.gst.access.domain.consent.AgentConsentPolicy
import com.dynokore.gst.access.domain.consent.ScopeItem
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.role.Permission
import java.time.LocalDate

/**
 * The scope item an agent operation needs (INV-REQ-913 behaviour 4: for agent operations an S in the permission matrix
 * also requires the scope item). A permission the table does not list needs an item nobody can name, so an agent is
 * refused it (deny by default).
 */
class ScopeItemRequirements(
    private val items: Map<Permission, ScopeItem>,
) {
    fun itemFor(permission: Permission): ScopeItem? = items[permission]
}

/**
 * The live check of an agent operation on a client entity: reads the consent record of the client now, in the caller's
 * transaction, and decides on that record alone (INV-REQ-865, INV-REQ-913). Keeps no state.
 */
class AgentScope(
    private val consents: ConsentRepository,
    private val requirements: ScopeItemRequirements,
) {
    fun permits(
        agent: IdentityRef,
        client: EntityRef,
        permission: Permission,
        on: LocalDate,
    ): Boolean {
        val item = requirements.itemFor(permission) ?: return false
        return AgentConsentPolicy.permits(consents.findLiveForClient(client), agent, client, item, on)
    }

    /** As [permits] for a non-operator consent-derived grant: the consent's agent is not compared (INV-Q-9320 A). */
    fun permitsClient(
        client: EntityRef,
        permission: Permission,
        on: LocalDate,
    ): Boolean {
        val item = requirements.itemFor(permission) ?: return false
        return AgentConsentPolicy.permitsClient(consents.findLiveForClient(client), client, item, on)
    }
}
