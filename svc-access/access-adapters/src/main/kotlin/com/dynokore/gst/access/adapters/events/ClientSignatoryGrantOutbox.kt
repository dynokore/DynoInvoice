package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.adapters.persistence.AuthorityHeadStore
import com.dynokore.gst.access.adapters.persistence.AuthorityIncarnationStore
import com.dynokore.gst.access.application.grant.RoleGrantState
import com.dynokore.gst.access.application.link.ClientSignatoryGrants
import com.dynokore.gst.access.domain.link.SignedLink
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * TPL-05: appends the AuthorityChanged role record of a signatory link (INV-Q-9222 A) through outbox-and-queue, in the
 * transaction the caller holds (CS-17), so a rolled-back issue publishes nothing and a failed append rolls the issue
 * back. The envelope is tenant-wide and keyed on the principal `link:<linkRef>` (INV-Q-9229 A). [nextVersion] draws
 * from access-svc's one authority sequence, once per record.
 */
class ClientSignatoryGrantOutbox(
    private val outbox: OutboxWriter,
    private val tenants: TenantContext,
    mapper: ObjectMapper,
    private val head: AuthorityHeadStore,
    incarnations: AuthorityIncarnationStore,
    private val nextVersion: () -> Long,
) : ClientSignatoryGrants {
    private val payloads = AuthorityChangedPayloads(mapper, incarnations)

    override fun issued(
        link: SignedLink,
        issuedAt: Instant,
    ) = append(link, RoleGrantState.ACTIVE, issuedAt)

    override fun removed(
        link: SignedLink,
        at: Instant,
    ) = append(link, RoleGrantState.REMOVED, at)

    private fun append(
        link: SignedLink,
        state: RoleGrantState,
        from: Instant,
    ) {
        val client = checkNotNull(link.client) { "only a link with a client entity has a role record" }
        val principal = AuthorityChangedPayloads.linkPrincipal(link.ref.value)
        val version = nextVersion()
        val json =
            payloads
                .clientSignatoryGrant(link.ref.value, client.value, state, from, link.expiresAt, nextVersion())
                .toString()
        outbox.append(
            listOf(OutboundEvent(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, json)),
            EventContexts.forAggregate(tenants.current(), EntityRef.TENANT_WIDE, principal),
        )
        head.advance(version)
    }
}
