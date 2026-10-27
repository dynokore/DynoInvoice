package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.domain.grant.ConsentCheck
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef

/**
 * Reads the `scope` string of the access contract. The contract's examples publish two forms only, `entity:<ref>` and
 * `tenant:<ref>`; no text defines the grammar of a group or an entity-and-period scope, so anything else is refused
 * (fail closed) until the owner rules the grammar. A tenant scope must name the bound tenant. `consent:<tenantRef>` is
 * the consent-derived scope (INV-Q-8868 A, the literal is Inference) and must name the bound tenant too; only grantRole
 * accepts it (see `GrantRequests.scopeOf`).
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
        val colon = text.indexOf(':')
        val kind = if (colon > 0) text.substring(0, colon) else ""
        val ref = if (colon >= 0) text.substring(colon + 1) else ""
        return when {
            text.isBlank() -> Parsed.Missing
            colon <= 0 || colon == text.length - 1 || !REF.matches(ref) -> Parsed.Unsupported(text)
            kind == "entity" -> Parsed.Ok(Scope.Entity(EntityRef(ref)))
            ref != boundTenant.value -> Parsed.Unsupported(text)
            kind == "tenant" -> Parsed.Ok(Scope.Tenant(boundTenant))
            kind == "consent" -> Parsed.Ok(Scope.ConsentDerived(boundTenant, NEVER_LIVE))
            else -> Parsed.Unsupported(text)
        }
    }

    /**
     * The check of a parsed consent-derived scope answers false (fail closed): a decision always runs on the scope that
     * `JooqGrantRepository` reloads with the live check, so a decision on the parsed object never allows.
     */
    private val NEVER_LIVE = ConsentCheck { false }

    /** A reference is letters, digits, underscore and hyphen, 1 to 64: no list, wildcard, range, path or space. */
    private val REF = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
}
