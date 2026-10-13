package com.dynokore.platform.rulepackkernel

/**
 * The opaque kind of pack (`Scope`, `Code catalogue`, `MDE`, `Schematron`, … —
 * packs.yaml's `pack_types`), carried as a plain string rather than an enum this
 * kernel would have to know the product's pack catalogue to define (ARC-16: L1
 * modules carry no GST vocabulary). [PackRegistry.resolve] is keyed on it exactly
 * as the product layer names it.
 */
@JvmInline
value class PackType(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "PackType must not be blank" }
    }

    override fun toString(): String = value
}
