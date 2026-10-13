package com.dynokore.platform.evidencestore

/**
 * The entity an evidence record belongs to — one of the two keys every
 * retrieval and export is scoped by, alongside [PeriodRef] (done-when:
 * "indexed retrieval and bulk export scoped to entity and period").
 *
 * CS-06: a value class, never a bare `String`, so an entity and a period
 * cannot be transposed by argument order at a call site.
 */
@JvmInline
value class EntityRef(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "EntityRef must not be blank" }
    }
}
