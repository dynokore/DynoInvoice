package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.TenantId

/**
 * The seven scope levels at which a value may be set (INV-REQ-1242 behaviour #1; gst-catalogue 1.2.0
 * `ScopeLevel`), from the broadest to the most specific. The declaration order IS the precedence: a later
 * level beats an earlier one (INV-REQ-1068 behaviour #1). `FEED` sits between entity and Access Point
 * connection (INV-T-2869 new_param.scope, ruling INV-Q-2934): client entity, AP connection, feed, entity,
 * group, tenant, installation.
 */
enum class ScopeLevel {
    INSTALLATION,
    TENANT,
    GROUP,
    ENTITY,
    FEED,
    AP_CONNECTION,
    CLIENT_ENTITY,
    ;

    /** Whether a setting at this level belongs to one entity of a tenant (and so carries `entity_id`). */
    val entityScoped: Boolean get() = this >= ENTITY
}

/** One level and the identifier of the thing at that level (a group, an entity, a feed ...). */
data class ScopeRef(
    val level: ScopeLevel,
    val id: String,
) {
    init {
        require(id.isNotBlank()) { "scope id must not be blank" }
    }

    override fun toString(): String = "$level:$id"
}

/**
 * Where a variation point is read: the chain of scopes that apply to one document or action, built from
 * what the caller knows. An absent level does not take part. The installation level is always present and
 * needs no identifier (there is one installation). A group lives inside one tenant (INV-Q-2934).
 */
class PolicyScope private constructor(
    val tenant: TenantId?,
    private val refs: Map<ScopeLevel, ScopeRef>,
    val entity: String?,
) {
    /** The scopes that apply, most specific first, ending with the installation. */
    fun chain(): List<ScopeRef> = ScopeLevel.entries.reversed().mapNotNull { refs[it] }

    fun ref(level: ScopeLevel): ScopeRef? = refs[level]

    override fun toString(): String = chain().joinToString(" > ")

    companion object {
        val INSTALLATION_REF = ScopeRef(ScopeLevel.INSTALLATION, "installation")

        /** Installation only: platform-wide, no tenant. */
        fun installation(): PolicyScope = PolicyScope(null, mapOf(ScopeLevel.INSTALLATION to INSTALLATION_REF), null)

        /** A tenant, optionally narrowed to one of its groups. */
        fun tenant(
            tenant: TenantId,
            group: String? = null,
        ): PolicyScope {
            val refs = tenantRefs(tenant)
            group?.let { refs[ScopeLevel.GROUP] = ScopeRef(ScopeLevel.GROUP, it) }
            return PolicyScope(tenant, refs, null)
        }

        /**
         * An entity of a tenant, optionally in a [group], and optionally narrowed to a [feed], an Access
         * Point connection or a client entity (under an agent consent).
         */
        fun entity(
            tenant: TenantId,
            entity: String,
            group: String? = null,
            feed: String? = null,
            apConnection: String? = null,
            clientEntity: String? = null,
        ): PolicyScope {
            val refs = tenantRefs(tenant)
            group?.let { refs[ScopeLevel.GROUP] = ScopeRef(ScopeLevel.GROUP, it) }
            refs[ScopeLevel.ENTITY] = ScopeRef(ScopeLevel.ENTITY, entity)
            feed?.let { refs[ScopeLevel.FEED] = ScopeRef(ScopeLevel.FEED, it) }
            apConnection?.let { refs[ScopeLevel.AP_CONNECTION] = ScopeRef(ScopeLevel.AP_CONNECTION, it) }
            clientEntity?.let { refs[ScopeLevel.CLIENT_ENTITY] = ScopeRef(ScopeLevel.CLIENT_ENTITY, it) }
            return PolicyScope(tenant, refs, entity)
        }

        private fun tenantRefs(tenant: TenantId): MutableMap<ScopeLevel, ScopeRef> {
            require(!tenant.isPlatform) { "the platform tenant has no tenant-level settings; use installation()" }
            return linkedMapOf(
                ScopeLevel.INSTALLATION to INSTALLATION_REF,
                ScopeLevel.TENANT to ScopeRef(ScopeLevel.TENANT, tenant.toString()),
            )
        }
    }
}
