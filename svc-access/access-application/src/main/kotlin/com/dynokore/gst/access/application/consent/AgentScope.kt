package com.dynokore.gst.access.application.consent

import com.dynokore.gst.access.domain.consent.AgentConsentPolicy
import com.dynokore.gst.access.domain.consent.ScopeItem
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.TenantRef
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
    /**
     * The operator check: the consent's agent is compared with the agent tenant of the operator's grant, by string
     * equality (INV-Q-9320 A: the tenant of the grant equals the `agentRef` of the consent). The identity of the
     * operator is not compared.
     */
    fun permits(
        operatorTenant: TenantRef,
        client: EntityRef,
        permission: Permission,
        on: LocalDate,
    ): Boolean = permitsClient(operatorTenant, client, permission, on)

    /**
     * The check of a consent-derived grant: the consent's agent is compared with the holder's agent
     * tenant, by string equality (INV-Q-9320 A: the tenant of the grant holder equals the `agentRef` of the consent).
     */
    fun permitsClient(
        holderTenant: TenantRef,
        client: EntityRef,
        permission: Permission,
        on: LocalDate,
    ): Boolean {
        val item = requirements.itemFor(permission) ?: return false
        val consent = consents.findLiveForClient(client)
        return consent != null &&
            consent.agent.value == holderTenant.value &&
            AgentConsentPolicy.permitsClient(consent, client, item, on)
    }
}
