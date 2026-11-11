package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.records.CounterpartyRequestRecord
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.records.LinkBindingRecord
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.COUNTERPARTY_REQUEST
import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.LINK_BINDING
import com.dynokore.gst.masterdata.application.request.CounterpartyRequests
import com.dynokore.gst.masterdata.application.request.LinkBindings
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.request.CounterpartyRequest
import com.dynokore.gst.masterdata.domain.request.LinkBinding
import com.dynokore.gst.masterdata.domain.request.RequestStatus
import com.dynokore.platform.outboxandqueue.UuidV7
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

/**
 * TPL-11. The counterparty requests of the bound tenant; the tenancy-jooq listeners add the tenant predicate to every
 * statement. A request id is `REQ-` and the 32 hex digits of a UUIDv7, 36 characters (the contract allows 40).
 */
class JooqCounterpartyRequests(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : CounterpartyRequests {
    override fun find(requestId: String): CounterpartyRequest? =
        dsl
            .selectFrom(COUNTERPARTY_REQUEST)
            .where(COUNTERPARTY_REQUEST.REQUEST_ID.eq(requestId))
            .fetchOne()
            ?.let(::request)

    override fun openFor(party: PartyRef): CounterpartyRequest? =
        dsl
            .selectFrom(COUNTERPARTY_REQUEST)
            .where(
                COUNTERPARTY_REQUEST.PARTY_REF.eq(party.value),
                COUNTERPARTY_REQUEST.STATUS.eq(RequestStatus.SENT.name),
            ).fetchOne()
            ?.let(::request)

    override fun save(request: CounterpartyRequest) {
        dsl
            .insertInto(COUNTERPARTY_REQUEST)
            .set(COUNTERPARTY_REQUEST.REQUEST_ID, request.requestId)
            .set(COUNTERPARTY_REQUEST.ENTITY, request.entity)
            .set(COUNTERPARTY_REQUEST.PARTY_REF, request.party.value)
            .set(COUNTERPARTY_REQUEST.CONTACT_EMAIL, request.contactEmail)
            .set(COUNTERPARTY_REQUEST.CONTACT_MOBILE, request.contactMobile)
            .set(COUNTERPARTY_REQUEST.STATUS, request.status.name)
            .set(COUNTERPARTY_REQUEST.LAST_REQUESTED_AT, request.lastRequestedAt.utc())
            .set(COUNTERPARTY_REQUEST.EXPIRES_AT, request.expiresAt.utc())
            .onConflict(COUNTERPARTY_REQUEST.TENANT_ID, COUNTERPARTY_REQUEST.REQUEST_ID)
            .doUpdate()
            .set(COUNTERPARTY_REQUEST.CONTACT_EMAIL, request.contactEmail)
            .set(COUNTERPARTY_REQUEST.CONTACT_MOBILE, request.contactMobile)
            .set(COUNTERPARTY_REQUEST.STATUS, request.status.name)
            .set(COUNTERPARTY_REQUEST.LAST_REQUESTED_AT, request.lastRequestedAt.utc())
            .set(COUNTERPARTY_REQUEST.EXPIRES_AT, request.expiresAt.utc())
            .execute()
    }

    override fun nextId(): String = "REQ-" + ids.next(clock.instant()).toString().replace("-", "")

    private fun request(record: CounterpartyRequestRecord) =
        CounterpartyRequest(
            requestId = record.requestId.column("request_id"),
            tenant = record.tenantId.column("tenant_id").toString(),
            entity = record.entity.column("entity"),
            party = PartyRef(record.partyRef.column("party_ref")),
            contactEmail = record.contactEmail.column("contact_email"),
            contactMobile = record.contactMobile,
            status = RequestStatus.valueOf(record.status.column("status")),
            lastRequestedAt = record.lastRequestedAt.column("last_requested_at").toInstant(),
            expiresAt = record.expiresAt.column("expires_at").toInstant(),
        )
}

/**
 * TPL-11. The link bindings of the bound tenant. [save] writes every column from the binding the domain computed, so
 * a repeat leaves the row equal; [recordEvent] keeps the greatest event id applied, which does not depend on the order
 * of delivery either.
 */
class JooqLinkBindings(
    private val dsl: DSLContext,
) : LinkBindings {
    override fun find(linkRef: String): LinkBinding? =
        dsl
            .selectFrom(LINK_BINDING)
            .where(LINK_BINDING.LINK_REF.eq(linkRef))
            .fetchOne()
            ?.let(::binding)

    override fun forRequest(requestId: String): List<LinkBinding> =
        dsl
            .selectFrom(LINK_BINDING)
            .where(LINK_BINDING.REQUEST_ID.eq(requestId))
            .fetch()
            .map(::binding)

    override fun save(binding: LinkBinding) {
        dsl
            .insertInto(LINK_BINDING)
            .set(LINK_BINDING.LINK_REF, binding.linkRef)
            .set(LINK_BINDING.TENANT, binding.tenant)
            .set(LINK_BINDING.REQUEST_ID, binding.requestId)
            .set(LINK_BINDING.EXPIRY, binding.expiry?.utc())
            .set(LINK_BINDING.INVALIDATED_AT, binding.invalidatedAt?.utc())
            .onConflict(LINK_BINDING.LINK_REF)
            .doUpdate()
            .set(LINK_BINDING.TENANT, binding.tenant)
            .set(LINK_BINDING.REQUEST_ID, binding.requestId)
            .set(LINK_BINDING.EXPIRY, binding.expiry?.utc())
            .set(LINK_BINDING.INVALIDATED_AT, binding.invalidatedAt?.utc())
            .execute()
    }

    /** Keeps the greatest id of the events applied to [linkRef]. */
    fun recordEvent(
        linkRef: String,
        eventId: String,
    ) {
        dsl
            .update(LINK_BINDING)
            .set(LINK_BINDING.LAST_EVENT_ID, DSL.greatest(LINK_BINDING.LAST_EVENT_ID, DSL.inline(eventId)))
            .where(LINK_BINDING.LINK_REF.eq(linkRef))
            .execute()
    }

    private fun binding(record: LinkBindingRecord) =
        LinkBinding(
            linkRef = record.linkRef.column("link_ref"),
            tenant = record.tenant,
            requestId = record.requestId,
            expiry = record.expiry?.toInstant(),
            invalidatedAt = record.invalidatedAt?.toInstant(),
        )
}
