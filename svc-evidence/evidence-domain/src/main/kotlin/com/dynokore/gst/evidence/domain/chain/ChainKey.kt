package com.dynokore.gst.evidence.domain.chain

/** The chain a record is appended to within one tenant: one chain per (tenant, entity, period) (INV-REQ-650 b2). */
data class ChainKey(
    val entityRef: String,
    val periodRef: String,
) {
    init {
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        require(periodRef.isNotBlank()) { "periodRef must not be blank" }
    }
}
