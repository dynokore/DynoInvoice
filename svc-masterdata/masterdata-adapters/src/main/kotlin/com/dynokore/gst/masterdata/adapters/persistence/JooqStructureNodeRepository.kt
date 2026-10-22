package com.dynokore.gst.masterdata.adapters.persistence

import com.dynokore.gst.masterdata.adapters.persistence.jooq.tables.references.STRUCTURE_NODE
import com.dynokore.gst.masterdata.application.counterparty.StructureNodeRepository
import com.dynokore.gst.masterdata.domain.counterparty.StructureNode
import com.dynokore.platform.outboxandqueue.UuidV7
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset

/** TPL-11. The structure nodes of the bound tenant; the tenancy-jooq listeners add the tenant predicate. */
class JooqStructureNodeRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : StructureNodeRepository {
    override fun all(): List<StructureNode> =
        dsl
            .selectFrom(STRUCTURE_NODE)
            .fetch()
            .map {
                StructureNode(
                    it.nodeRef.column("node_ref"),
                    it.kind.column("kind"),
                    it.parentRef,
                    it.retired.column("retired"),
                    it.uen,
                    it.gstn,
                    it.structureVersion.column("structure_version"),
                )
            }

    override fun apply(node: StructureNode): Boolean {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        return dsl
            .insertInto(STRUCTURE_NODE)
            .set(STRUCTURE_NODE.ID, ids.next(clock.instant()))
            .set(STRUCTURE_NODE.NODE_REF, node.nodeRef)
            .set(STRUCTURE_NODE.KIND, node.kind)
            .set(STRUCTURE_NODE.PARENT_REF, node.parentRef)
            .set(STRUCTURE_NODE.RETIRED, node.retired)
            .set(STRUCTURE_NODE.UEN, node.uen)
            .set(STRUCTURE_NODE.GSTN, node.gstn)
            .set(STRUCTURE_NODE.STRUCTURE_VERSION, node.structureVersion)
            .set(STRUCTURE_NODE.CREATED_AT, now)
            .set(STRUCTURE_NODE.UPDATED_AT, now)
            .set(STRUCTURE_NODE.VERSION, 0L)
            .onConflict(STRUCTURE_NODE.TENANT_ID, STRUCTURE_NODE.NODE_REF)
            .doUpdate()
            .set(STRUCTURE_NODE.KIND, node.kind)
            .set(STRUCTURE_NODE.PARENT_REF, node.parentRef)
            .set(STRUCTURE_NODE.RETIRED, node.retired)
            .set(STRUCTURE_NODE.UEN, node.uen)
            .set(STRUCTURE_NODE.GSTN, node.gstn)
            .set(STRUCTURE_NODE.STRUCTURE_VERSION, node.structureVersion)
            .set(STRUCTURE_NODE.UPDATED_AT, now)
            .set(STRUCTURE_NODE.VERSION, STRUCTURE_NODE.VERSION.plus(1L))
            .where(STRUCTURE_NODE.STRUCTURE_VERSION.lt(node.structureVersion))
            .execute() == 1
    }
}
