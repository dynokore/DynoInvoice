package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.INBOUND_EVENT
import com.dynokore.gst.canonical.application.inbound.InboundEventStore
import com.dynokore.gst.canonical.domain.inbound.BuyerDisposition
import com.dynokore.gst.canonical.domain.inbound.Existing
import com.dynokore.gst.canonical.domain.inbound.FallbackKey
import com.dynokore.gst.canonical.domain.inbound.InboundEvent
import com.dynokore.gst.canonical.domain.inbound.InboundKeys
import com.dynokore.gst.canonical.domain.inbound.InboundKind
import com.dynokore.gst.canonical.domain.inbound.UuidKey
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

/**
 * TPL-11. jOOQ only. The tenant predicate is the framework's and forced row-level security stays behind it. The
 * fallback comparison is plain equality of every key column, so a row whose fallback columns are null never matches.
 */
class JooqInboundEventStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : InboundEventStore {
    override fun append(
        event: InboundEvent,
        docUuid: UUID,
        version: Int,
    ) {
        val key = InboundKeys.fallbackKey(event)
        dsl
            .insertInto(INBOUND_EVENT)
            .set(INBOUND_EVENT.TENANT_ID, tenants.current().value)
            .set(INBOUND_EVENT.ENTITY_ID, event.entityId)
            .set(INBOUND_EVENT.DOC_UUID, docUuid)
            .set(INBOUND_EVENT.VERSION, version)
            .set(INBOUND_EVENT.EVENT_KIND, event.kind.name)
            .set(INBOUND_EVENT.DOCUMENT_FUNCTION, event.documentFunction.name)
            .set(INBOUND_EVENT.SUPPLIER_KEY, key?.supplierKey)
            .set(INBOUND_EVENT.DOCUMENT_NUMBER, key?.documentNumber)
            .set(INBOUND_EVENT.DOCUMENT_YEAR, key?.documentYear?.toShort())
            .set(INBOUND_EVENT.SUPPLIER_ENDPOINT_ID, event.supplierEndpointId)
            .set(INBOUND_EVENT.SUPPLIER_DOC_UUID, event.supplierDocUuid)
            .set(INBOUND_EVENT.BUYER_DISPOSITION, event.buyerDisposition?.name)
            .execute()
    }

    override fun findByUuidKey(uuidKey: UuidKey): List<Existing> {
        val endpoint = uuidKey.supplierEndpointId ?: return emptyList()
        val docUuids =
            dsl
                .selectDistinct(INBOUND_EVENT.DOC_UUID)
                .from(INBOUND_EVENT)
                .where(INBOUND_EVENT.ENTITY_ID.eq(uuidKey.entityId))
                .and(INBOUND_EVENT.SUPPLIER_ENDPOINT_ID.eq(endpoint))
                .and(INBOUND_EVENT.SUPPLIER_DOC_UUID.eq(uuidKey.supplierDocUuid))
                .fetch(INBOUND_EVENT.DOC_UUID)
                .filterNotNull()
        return docUuids.map { existing(it) }
    }

    /** The rows equal to the key, kept for the Doc UUIDs whose highest version is that row (no table alias). */
    override fun findByFallbackKey(fallbackKey: FallbackKey): List<Existing> {
        val matching =
            dsl
                .select(INBOUND_EVENT.DOC_UUID, INBOUND_EVENT.VERSION)
                .from(INBOUND_EVENT)
                .where(INBOUND_EVENT.ENTITY_ID.eq(fallbackKey.entityId))
                .and(INBOUND_EVENT.DOCUMENT_FUNCTION.eq(fallbackKey.documentFunction.name))
                .and(INBOUND_EVENT.SUPPLIER_KEY.eq(fallbackKey.supplierKey))
                .and(INBOUND_EVENT.DOCUMENT_NUMBER.eq(fallbackKey.documentNumber))
                .and(INBOUND_EVENT.DOCUMENT_YEAR.eq(fallbackKey.documentYear.toShort()))
                .fetch()
                .map { checkNotNull(it.get(INBOUND_EVENT.DOC_UUID)) to checkNotNull(it.get(INBOUND_EVENT.VERSION)) }
        if (matching.isEmpty()) return emptyList()
        val latest =
            dsl
                .select(INBOUND_EVENT.DOC_UUID, DSL.max(INBOUND_EVENT.VERSION))
                .from(INBOUND_EVENT)
                .where(INBOUND_EVENT.DOC_UUID.`in`(matching.map { it.first }))
                .groupBy(INBOUND_EVENT.DOC_UUID)
                .fetch()
                .associate { checkNotNull(it.value1()) to it.value2() }
        return matching.filter { (doc, version) -> latest[doc] == version }.map { existing(it.first) }
    }

    override fun previousDisposition(docUuid: UUID): BuyerDisposition? =
        dsl
            .select(INBOUND_EVENT.BUYER_DISPOSITION)
            .from(INBOUND_EVENT)
            .where(INBOUND_EVENT.DOC_UUID.eq(docUuid))
            .and(INBOUND_EVENT.EVENT_KIND.eq(InboundKind.RECEIPT.name))
            .orderBy(INBOUND_EVENT.VERSION.desc())
            .limit(1)
            .fetchOne(INBOUND_EVENT.BUYER_DISPOSITION)
            ?.let { BuyerDisposition.valueOf(it) }

    /** The supplier Doc UUID of the highest-version row of [docUuid] that has one. */
    private fun existing(docUuid: UUID): Existing =
        Existing(
            docUuid,
            dsl
                .select(INBOUND_EVENT.SUPPLIER_DOC_UUID)
                .from(INBOUND_EVENT)
                .where(INBOUND_EVENT.DOC_UUID.eq(docUuid))
                .and(INBOUND_EVENT.SUPPLIER_DOC_UUID.isNotNull)
                .orderBy(INBOUND_EVENT.VERSION.desc())
                .limit(1)
                .fetchOne(INBOUND_EVENT.SUPPLIER_DOC_UUID),
        )
}
