package com.dynokore.platform.rulepackkernel

/**
 * The opaque identifier of one pack lineage (all versions of the same pack over
 * time share it) — carried on `RulePackStaged`'s "pack identifier" field
 * (packs.yaml: `RulePackStaged` payload).
 *
 * A plain `String`, not a GST-specific enum of pack names (`Scope`, `MDE`, …
 * packs.yaml's `pack_types`): L1 modules carry no GST vocabulary (ARC-16). The
 * catalogue of real pack identifiers belongs to the product layer
 * (`product-runtime`/`rules-svc`), never to this kernel.
 */
@JvmInline
value class PackId(val value: String) {
    init {
        require(value.isNotBlank()) { "PackId must not be blank" }
    }

    override fun toString(): String = value
}
