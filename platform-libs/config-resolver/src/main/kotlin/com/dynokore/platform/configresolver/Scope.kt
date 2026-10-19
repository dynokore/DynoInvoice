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

    /**
     * The one scope a value is set at (INV-REQ-1242 behaviour #1).
     *
     * @throws IllegalArgumentException when this scope has no [level], or an entity-scoped level has no entity.
     */
    fun target(level: ScopeLevel): SettingTarget {
        val ref = requireNotNull(refs[level]) { "the scope $this has no $level" }
        return SettingTarget(
            if (level == ScopeLevel.INSTALLATION) null else tenant,
            ref,
            if (level.entityScoped) entity else null,
        )
    }

    /** This scope cut at [level]: the chain that applied at that level, e.g. for a prior value. */
    fun narrowedTo(level: ScopeLevel): PolicyScope {
        require(refs.containsKey(level)) { "the scope $this has no $level" }
        val kept = refs.filterKeys { it <= level }
        return PolicyScope(tenant, kept, if (level.entityScoped) entity else null)
    }

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
        @Suppress("LongParameterList") // a public factory with defaulted parameters; INV-Q-4629 (CS-50)
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

/**
 * Where one value is set: a scope level and its identifier, the tenant (none at installation) and, for an
 * entity-scoped level (entity, feed, AP connection, client entity), the entity it belongs to.
 */
data class SettingTarget(
    val tenant: TenantId?,
    val ref: ScopeRef,
    val entity: String?,
) {
    init {
        require((ref.level == ScopeLevel.INSTALLATION) == (tenant == null)) {
            "an installation target has no tenant, every other target has one"
        }
        require(tenant == null || !tenant.isPlatform) { "the platform tenant has no tenant-level settings" }
        require(ref.level.entityScoped == (entity != null)) {
            "${ref.level} ${if (ref.level.entityScoped) "needs" else "has no"} entity"
        }
    }

    val level: ScopeLevel get() = ref.level

    /** The scope text of the audit entry and of `PolicyChanged.scope`: `INSTALLATION`, or `LEVEL:id`. */
    val scopeText: String get() = if (ref.level == ScopeLevel.INSTALLATION) "INSTALLATION" else ref.toString()
}
