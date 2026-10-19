package com.dynokore.gst.tenancy.application.structure

/** The level of an organisation node (INV-REQ-850). */
enum class NodeType { TENANT, GROUP, ENTITY, BUSINESS_UNIT }

/** What happened to a node (the asyncapi enum of OrganisationStructureChanged; INV-API-3327). */
enum class ChangeType { CREATED, MOVED, RETIRED, UPDATED }

/** The node's full state after the change, the same object getEntityStructure returns (INV-Q-3324 (a)). */
data class StructureNodeState(
    val kind: NodeType,
    val parentRef: String?,
    val retired: Boolean,
    val name: String,
    val code: String?,
    val structureVersion: Long,
)

/** One structure change, raised in the command transaction that makes it. */
data class StructureChange(
    val tenant: java.util.UUID,
    val entityRef: String,
    val nodeType: NodeType,
    val nodeRef: String,
    val changeType: ChangeType,
    val parentRef: String?,
    val previousParentRef: String?,
    val node: StructureNodeState,
) {
    init {
        require(structureVersionPositive()) { "structureVersion starts at 1" }
    }

    private fun structureVersionPositive() = node.structureVersion >= 1
}

/** Publishes every structure change through outbox-and-queue in the caller's transaction (TPL-05, CS-17). */
interface StructureChangePublisher {
    fun publish(change: StructureChange)
}
