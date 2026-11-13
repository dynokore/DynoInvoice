package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.adapters.persistence.AuthorityHeadStore
import com.dynokore.gst.access.adapters.persistence.AuthorityIncarnationStore
import com.dynokore.gst.access.application.principal.PrincipalRevocationPublisher
import com.dynokore.gst.access.domain.principal.ServicePrincipal
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Clock

/**
 * TPL-05: maps the revocation of a service principal to the contract event AuthorityChanged with changeKind
 * REVOCATION and a principalRecord in state REVOKED (INV-API-3325, schema `authority-changed-payload`), appended in the
 * command transaction the caller holds: one event for the principal, not one per entity.
 */
class PrincipalRevocationOutbox(
    private val outbox: OutboxWriter,
    mapper: ObjectMapper,
    private val clock: Clock,
    private val head: AuthorityHeadStore,
    incarnations: AuthorityIncarnationStore,
) : PrincipalRevocationPublisher {
    private val payloads = AuthorityChangedPayloads(mapper, incarnations)

    override fun publishRevoked(
        principal: ServicePrincipal,
        nextVersion: () -> Long,
    ) {
        // one principalRecord event for the principal (INV-Q-8972 A): a replica voids every grant record below its
        // version
        val context =
            EventContexts.forAggregate(TenantId.of(principal.tenant.value), EntityRef.TENANT_WIDE, principal.ref)
        val version = nextVersion()
        outbox.append(
            listOf(
                OutboundEvent(
                    PlatformEventCatalogue.AUTHORITY_CHANGED.eventType,
                    payload(principal, version).toString(),
                ),
            ),
            context,
        )
        head.advance(version)
    }

    internal fun payload(
        principal: ServicePrincipal,
        authorityVersion: Long,
    ): ObjectNode = payloads.principalRevoked(principal.ref, authorityVersion, clock.instant())
}
