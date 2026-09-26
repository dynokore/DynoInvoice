package com.dynokore.platform.evidencestore

/**
 * The prescribed accounting period an evidence record belongs to — the second
 * of the two keys every retrieval and export is scoped by, alongside
 * [EntityRef] (FR-9.15: indexed by, among other keys, "prescribed period").
 *
 * CS-06: a value class, never a bare `String`.
 */
@JvmInline
value class PeriodRef(val value: String) {
    init {
        require(value.isNotBlank()) { "PeriodRef must not be blank" }
    }
}
