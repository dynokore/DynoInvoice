package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef

/**
 * Reads the `scope` string of the access contract. The contract's examples publish two forms only, `entity:<ref>` and
 * `tenant:<ref>`; no text defines the grammar of a group, an entity-and-period or a consent-derived scope, so anything
 * else is refused (fail closed) until the owner rules the grammar. A tenant scope must name the bound tenant.
 */
object ScopeParser {
    sealed interface Parsed {
        data class Ok(
            val scope: Scope,
        ) : Parsed

        /** Blank: a grant without a scope (INV-REQ-859 behaviour 4). */
        data object Missing : Parsed

        data class Unsupported(
            val text: String,
        ) : Parsed
    }

    fun parse(
        text: String,
        boundTenant: TenantRef,
    ): Parsed {
        if (text.isBlank()) return Parsed.Missing
        val colon = text.indexOf(':')
        if (colon <= 0 || colon == text.length - 1) return Parsed.Unsupported(text)
        val kind = text.substring(0, colon)
        val ref = text.substring(colon + 1)
        if (!REF.matches(ref)) return Parsed.Unsupported(text)
        return when (kind) {
            "entity" -> Parsed.Ok(Scope.Entity(EntityRef(ref)))
            "tenant" -> if (ref == boundTenant.value) Parsed.Ok(Scope.Tenant(boundTenant)) else Parsed.Unsupported(text)
            else -> Parsed.Unsupported(text)
        }
    }

    /** A reference is letters, digits, underscore and hyphen, 1 to 64: no list, wildcard, range, path or space. */
    private val REF = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
}
