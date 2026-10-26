package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.adapters.http.ScopeParser
import com.dynokore.gst.access.application.grant.EntityDirectory
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * TPL-05: maps the revocation of a service principal to the contract event AuthorityChanged with changeKind
 * REVOCATION and a principalRecord in state REVOKED (INV-API-3325, schema `authority-changed-payload`), appended in the
 * command transaction the caller holds: one event per entity the principal's scope reaches (an entity scope is that
 * entity, a tenant scope every entity of the tenant).
 */
class PrincipalRevocationOutbox(
    private val outbox: OutboxWriter,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val directory: EntityDirectory,
) : PrincipalRevocationPublisher {
    override fun publishRevoked(
        principal: ServicePrincipal,
        nextVersion: () -> Long,
    ) {
        val tenant = TenantId.of(principal.tenant.value)
        // a domain event cannot carry the tenant-wide entity: the revocation is keyed on each entity the scope reaches
        val scope =
            (ScopeParser.parse(principal.scope, principal.tenant) as? ScopeParser.Parsed.Ok)?.scope
                ?: error("the scope of a stored principal is unreadable")
        directory.entitiesCoveredBy(scope).sortedBy { it.value }.forEach { entity ->
            val context = EventContexts.forAggregate(tenant, EntityRef(entity.value), principal.ref)
            outbox.append(
                listOf(
                    OutboundEvent(
                        PlatformEventCatalogue.AUTHORITY_CHANGED.eventType,
                        payload(principal, nextVersion()).toString(),
                    ),
                ),
                context,
            )
        }
    }

    internal fun payload(
        principal: ServicePrincipal,
        authorityVersion: Long,
    ): ObjectNode {
        val root = mapper.createObjectNode()
        root.put("changeKind", "REVOCATION")
        root.put("principalRef", principal.ref)
        root.put("authorityVersion", authorityVersion)
        root.putObject("validity").put("from", ISO.format(clock.instant().atOffset(ZoneOffset.UTC)))
        root.putObject("principalRecord").put("state", "REVOKED")
        return root
    }

    private companion object {
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
