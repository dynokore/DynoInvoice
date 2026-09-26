package com.dynokore.platform.tenancykernel

import java.util.UUID

/**
 * The tenant identifier every tenant-scoped query path carries (FR-G.5; CS-06:
 * identifiers are value classes, never a bare `String`/`UUID`).
 *
 * [toString] is exactly the canonical UUID text: this is the value
 * `TransactionRunner` binds with `set_config('app.tenant_id', …, true)` and the
 * RLS policy compares against (CS-19), so it must round-trip through
 * `::uuid` casts unchanged.
 */
@JvmInline
value class TenantId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun of(text: String): TenantId = TenantId(UUID.fromString(text))
    }
}
