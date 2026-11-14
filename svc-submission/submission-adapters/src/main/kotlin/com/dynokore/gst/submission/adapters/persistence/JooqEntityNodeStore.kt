package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.application.gstn.EntityNode
import com.dynokore.gst.submission.application.gstn.EntityNodes
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.EntityNode as EntityNodeTable

/**
 * [EntityNodes] over `submission.entity_node` in one transaction of the bound tenant: `insert ... on conflict do
 * nothing` (true when one row), else an `update` guarded by a lower stored structure version (true when one row). No
 * select before the write, so concurrent writers cannot race the result; forced row level security applies. Values
 * are never logged.
 */
class JooqEntityNodeStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : EntityNodes {
    private val n = EntityNodeTable.ENTITY_NODE

    override fun record(
        tenant: TenantId,
        node: EntityNode,
    ): Boolean =
        runner.inTransaction {
            val inserted =
                dsl
                    .insertInto(n)
                    .set(n.TENANT_ID, tenant.value)
                    .set(n.NODE_REF, node.nodeRef)
                    .set(n.KIND, node.kind)
                    .set(n.PARENT_REF, node.parentRef)
                    .set(n.GSTN, node.gstn)
                    .set(n.STRUCTURE_VERSION, node.structureVersion)
                    .onConflict(n.TENANT_ID, n.NODE_REF)
                    .doNothing()
                    .execute()
            inserted == 1 ||
                dsl
                    .update(n)
                    .set(n.KIND, node.kind)
                    .set(n.PARENT_REF, node.parentRef)
                    .set(n.GSTN, node.gstn)
                    .set(n.STRUCTURE_VERSION, node.structureVersion)
                    .where(n.TENANT_ID.eq(tenant.value))
                    .and(n.NODE_REF.eq(node.nodeRef))
                    .and(n.STRUCTURE_VERSION.lt(node.structureVersion))
                    .execute() == 1
        }

    override fun find(
        tenant: TenantId,
        nodeRef: String,
    ): EntityNode? =
        runner.inTransaction {
            dsl
                .select(n.NODE_REF, n.KIND, n.PARENT_REF, n.GSTN, n.STRUCTURE_VERSION)
                .from(n)
                .where(n.TENANT_ID.eq(tenant.value))
                .and(n.NODE_REF.eq(nodeRef))
                .fetchOne()
                ?.let {
                    EntityNode(
                        nodeRef = checkNotNull(it[n.NODE_REF]),
                        kind = checkNotNull(it[n.KIND]),
                        parentRef = it[n.PARENT_REF],
                        gstn = it[n.GSTN],
                        structureVersion = checkNotNull(it[n.STRUCTURE_VERSION]),
                    )
                }
        }
}
