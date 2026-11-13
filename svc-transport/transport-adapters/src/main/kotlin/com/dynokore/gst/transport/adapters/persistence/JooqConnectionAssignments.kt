package com.dynokore.gst.transport.adapters.persistence

import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApConnection.Companion.AP_CONNECTION
import com.dynokore.gst.transport.application.ConnectionAssignments
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.TenantRef
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.util.UUID

/**
 * "Which other connection of the tenant holds this entity" (SUB-018, INV-T-18675; ruling INV-Q-18664 A): the
 * connection whose `entity_refs` contain the entity, other than [connectionRef] itself. The tenant predicate and the
 * row-level security of the bound tenant keep the lookup to the tenant's own connections. When two connections hold the
 * entity (data that predates the rule), the one with the smaller `adapter_id` is named, so the answer is stable.
 */
class JooqConnectionAssignments(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : ConnectionAssignments {
    override fun otherConnectionHolding(
        tenant: TenantRef,
        entity: EntityRef,
        connectionRef: String,
    ): String? =
        runner.inTransaction {
            dsl
                .select(AP_CONNECTION.ADAPTER_ID)
                .from(AP_CONNECTION)
                .where(AP_CONNECTION.TENANT_ID.eq(UUID.fromString(tenant.value)))
                .and(AP_CONNECTION.ADAPTER_ID.ne(connectionRef))
                .and(AP_CONNECTION.ENTITY_REFS.contains(arrayOf(entity.value)))
                .orderBy(AP_CONNECTION.ADAPTER_ID)
                .limit(1)
                .fetchOne(AP_CONNECTION.ADAPTER_ID)
        }
}
