package com.dynokore.gst.payload.adapters.build

import com.dynokore.gst.payload.application.build.FamilyRequest
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantId

/**
 * The family lookup of VP-71 (INV-REQ-2467): the option key [policy] resolves for the connection of the request, at the
 * document date (`on`, the transaction date, inclusive; INV-Q-21771 A). The date is the request's, never the clock
 * (INV-REQ-1351). A request without a connection or without a date fails closed with an [IllegalStateException]
 * naming the entity, never a default family (NIST SP 800-53 SC-24). [tenant] is the tenant bound to the call.
 */
class ConnectionFamilyLookup(
    private val policy: PolicyReader,
    private val tenant: () -> TenantId,
) : FamilyLookup {
    override fun optionKeyOf(request: FamilyRequest): String? {
        val entityRef = request.entityRef
        val connection = request.connectionRef ?: error("no connection stored for the document of entity $entityRef")
        val on = request.on ?: error("no transaction date stored for the document of entity $entityRef")
        val scope = PolicyScope.entity(tenant(), entityRef, apConnection = connection)
        return policy
            .resolve(FAMILY_KEY, scope, on)
            .selected
            .singleOrNull()
            ?.key
    }

    private companion object {
        /** The variation point of the specification family of an Access Point connection. */
        val FAMILY_KEY = VariationPointKey("VP-71")
    }
}
