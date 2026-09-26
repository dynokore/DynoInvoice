package com.dynokore.platform.rulepackkernel

/**
 * The pack's own version label, exactly as the signed manifest states it (a
 * release-train or semantic string minted by `control-plane-svc`, e.g.
 * `"2026.10.1"`). Opaque to this kernel: [PackRegistry.resolve] never orders or
 * compares two labels against each other — resolution is by transaction/business
 * date over immutable versions (INV-ADR-263, INV-BR-215), never by version
 * ordering, so this type need be no more than an identifier.
 */
@JvmInline
value class PackVersionLabel(val value: String) {
    init {
        require(value.isNotBlank()) { "PackVersionLabel must not be blank" }
    }

    override fun toString(): String = value
}
