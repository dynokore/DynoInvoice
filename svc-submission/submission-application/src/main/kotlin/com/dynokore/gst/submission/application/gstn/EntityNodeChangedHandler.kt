package com.dynokore.gst.submission.application.gstn

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext

/** The members of a structure event the consumer reads; [structureVersion] is null when the event carries no node. */
data class EntityNodeChangedData(
    val nodeRef: String,
    val kind: String,
    val parentRef: String?,
    val gstn: String?,
    val structureVersion: Long?,
)

/** Reads the event data JSON of a structure event (the JSON library stays in the adapters, ARC-03). */
fun interface EntityNodeChangedDataReader {
    /** @throws ContractViolation when a member is missing or malformed. */
    fun read(dataJson: String): EntityNodeChangedData
}

/**
 * Keeps the newest version of each ENTITY and GROUP node (INV-REQ-978, INV-T-22285). An event without node, another
 * kind, or a repeated or older version is IGNORED. A [ContractViolation] of the reader propagates and stores nothing.
 * No message carries a GSTN value.
 */
class EntityNodeChangedHandler(
    private val tenants: TenantContext,
    private val reader: EntityNodeChangedDataReader,
    private val nodes: EntityNodes,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome {
        val data = reader.read(event.dataJson)
        val version = data.structureVersion
        val kept = data.kind == EntityNode.KIND_ENTITY || data.kind == EntityNode.KIND_GROUP
        return if (version != null && kept) {
            if (data.gstn != null && data.gstn.isBlank()) throw ContractViolation("gstn is blank")
            val node = EntityNode(data.nodeRef, data.kind, data.parentRef, data.gstn, version)
            if (nodes.record(tenants.current(), node)) HandleOutcome.APPLIED else HandleOutcome.IGNORED
        } else {
            HandleOutcome.IGNORED
        }
    }
}
