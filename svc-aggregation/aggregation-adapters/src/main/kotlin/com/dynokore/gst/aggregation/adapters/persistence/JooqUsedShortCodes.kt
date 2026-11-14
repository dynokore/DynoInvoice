package com.dynokore.gst.aggregation.adapters.persistence

import com.dynokore.gst.aggregation.adapters.events.ShortCodeUsedOutbox
import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.UsedShortCode.Companion.USED_SHORT_CODE
import com.dynokore.gst.aggregation.application.NewAggregate
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

/**
 * The short codes used in an aggregate number (INV-Q-11266 A, INV-AC-2779). For each code of a stored aggregate the row
 * is inserted `on conflict do nothing`; only an inserted row is the first use and appends `ShortCodeUsed`. Call inside
 * the transaction that stores the aggregate: the row and the outbox row then commit or roll back together.
 */
class JooqUsedShortCodes(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val events: ShortCodeUsedOutbox,
) {
    fun record(
        aggregateId: UUID,
        aggregate: NewAggregate,
    ) {
        first(ENTITY, aggregate.entityCode, aggregate.entityRef, aggregateId, aggregate)
        first(BUSINESS_UNIT, aggregate.businessUnitCode, aggregate.businessUnitRef, aggregateId, aggregate)
    }

    private fun first(
        nodeKind: String,
        code: String,
        nodeRef: String,
        aggregateId: UUID,
        aggregate: NewAggregate,
    ) {
        val inserted =
            dsl
                .insertInto(USED_SHORT_CODE)
                .set(USED_SHORT_CODE.TENANT_ID, tenants.current().value)
                .set(USED_SHORT_CODE.CODE, code)
                .set(USED_SHORT_CODE.NODE_KIND, nodeKind)
                .set(USED_SHORT_CODE.FIRST_AGGREGATE_ID, aggregateId.toString())
                .set(USED_SHORT_CODE.FIRST_USED_AT, DSL.currentOffsetDateTime())
                .onConflict(USED_SHORT_CODE.TENANT_ID, USED_SHORT_CODE.CODE)
                .doNothing()
                .execute()
        if (inserted == 1) {
            events.used(aggregate.entityRef, code, nodeKind, nodeRef, aggregateId, aggregate.documentNumber)
        }
    }

    private companion object {
        const val ENTITY = "ENTITY"
        const val BUSINESS_UNIT = "BUSINESS_UNIT"
    }
}
