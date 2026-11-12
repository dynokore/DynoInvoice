package com.dynokore.gst.canonical.application.entity

import java.util.UUID

/** One entity constitution state carried by an EntityProfileChanged event; `version` is node.structureVersion. */
data class EntityConstitutionFact(
    val tenantId: UUID,
    val entityRef: String,
    val constitution: String,
    val version: Long,
) {
    init {
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        require(constitution.isNotBlank()) { "constitution must not be blank" }
        require(version >= 1) { "version must be at least 1" }
    }
}

/** The row held per (tenant, entity). */
data class EntityConstitutionRow(
    val constitution: String,
    val version: Long,
)

/** Idempotent-consumer version guard (event-carried state transfer). Pure: no Spring, jOOQ or transaction type. */
object EntityConstitutionRule {
    /** Null means no write: the held row has a version greater than or equal to the fact's. */
    fun next(
        current: EntityConstitutionRow?,
        fact: EntityConstitutionFact,
    ): EntityConstitutionRow? =
        if (current != null && current.version >= fact.version) null else EntityConstitutionRow(fact.constitution, fact.version)
}

/** Port of the local entity constitution table, keyed by tenant and entity; the tenant is bound by the transaction. */
interface EntityConstitutionStore {
    fun apply(fact: EntityConstitutionFact)
}
