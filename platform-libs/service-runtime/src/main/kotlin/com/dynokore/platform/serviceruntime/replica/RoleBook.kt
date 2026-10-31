package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Duration

/** `rolesAt` was asked beyond the lag bound or before the first snapshot: nothing is read (INV-Q-9227 A). */
internal class RoleAnswerUnavailable(
    message: String,
) : IllegalStateException(message)

/**
 * The role grants the authority replica holds (INV-Q-8972 A) and the questions answered from them: which roles a
 * principal holds at a level, and who holds a role. A grant counts only while it is active, inside its validity
 * window and not voided by a principal record of a higher version. [index] maps (tenant, role) to the keys that
 * carry it, so a holders query examines those keys only.
 */
internal class RoleBook(
    private val clock: Clock,
    private val lagBound: Duration,
    private val tracker: ReplicaLagTracker,
    private val consents: EventFedReplica<ConsentKey, AuthorityRecord.ConsentRecord>,
    private val principals: EventFedReplica<PrincipalKey, PrincipalRevocation>,
    private val structure: StructureReplica?,
    tombstoneTtl: Duration? = null,
) {
    val grants =
        EventFedReplica<RoleGrantKey, RoleGrant>(
            "authority-role-grant",
            clock,
            lagBound,
            tracker = tracker,
            partitionOf = { it.tenant to it.principalId },
        )
    private val index = RoleIndex()

    val tombstones = TombstoneLog(clock, tombstoneTtl, grants.store)

    fun accept(
        tenant: TenantId,
        principalId: String,
        version: Long,
        record: AuthorityRecord.RoleGrantRecord,
    ) {
        val key = RoleGrantKey(tenant, principalId, record.grantRef)
        val before = grants.peek(key)
        val entryBefore = grants.store.get(key)
        if (record.state == RoleGrantState.ACTIVE) {
            grants.apply(ReplicaChange.Upsert(key, record.grant, version))
        } else {
            grants.apply(ReplicaChange.Remove(key, version))
        }
        // The index follows what the store holds after the event, so an ignored (older) event leaves it unchanged.
        val after = grants.peek(key)
        if (before != null && before.role != after?.role) index.remove(key, before.role)
        if (after != null) index.add(key, after.role)
        tombstones.track(key, entryBefore)
    }

    /** Drops every role grant of the principal, keeping each version as a tombstone (a record-less event). */
    fun tombstone(
        tenant: TenantId,
        principalId: String,
    ) {
        val held = grants.heldIn(tenant to principalId)
        val entries = held.keys.associateWith { grants.store.get(it) }
        held.forEach { (key, grant) -> index.remove(key, grant.role) }
        grants.tombstoneIn(tenant to principalId)
        entries.forEach { (key, entry) -> tombstones.track(key, entry) }
    }

    /** The roles [principalId] holds in [tenant] at [level] (and [entityRef] where the level is entity-scoped). */
    fun rolesAt(
        tenant: TenantId,
        principalId: String,
        level: ScopeLevel,
        entityRef: String?,
        deadline: Deadline,
    ): Set<String> {
        if (!tracker.lag().isWithin(lagBound)) {
            throw RoleAnswerUnavailable("the authority replica is beyond its lag bound or has no snapshot")
        }
        val owner = if (level == ScopeLevel.INSTALLATION) TenantId.PLATFORM else tenant
        val voidBelow = voidBelow(owner, principalId)
        val now = clock.instant()
        val counts = counter(tenant, level, entityRef, deadline)
        return grants.store
            .heldEntries(owner to principalId) { true }
            .values
            .filter { it.version >= voidBelow && it.value.isValidAt(now) && counts(it.value) }
            .mapTo(mutableSetOf()) { it.value.role }
    }

    /** The principals holding [role] at TENANT or INSTALLATION scope; any other level holds none. */
    fun holdersAt(
        tenant: TenantId,
        role: String,
        level: ScopeLevel,
    ): Set<String> {
        val installation = level == ScopeLevel.INSTALLATION
        val owner = if (installation) TenantId.PLATFORM else tenant
        val scope = if (installation) GrantScope.INSTALLATION else GrantScope.TENANT
        if (!installation && level != ScopeLevel.TENANT) return emptySet()
        return holders(owner, role) {
            it.scope == scope &&
                (level == ScopeLevel.INSTALLATION || it.scopeRef == tenant.toString())
        }
    }

    /** The principals holding [role] on [entityRef] of [tenant]: the ENTITY row of [rolesAt]. */
    fun holdersOn(
        tenant: TenantId,
        role: String,
        entityRef: String,
        deadline: Deadline,
    ): Set<String> {
        val counts = counter(tenant, ScopeLevel.ENTITY, entityRef, deadline)
        return holders(tenant, role, counts)
    }

    private fun holders(
        tenant: TenantId,
        role: String,
        counts: (RoleGrant) -> Boolean,
    ): Set<String> {
        val now = clock.instant()
        return index
            .keysOf(tenant, role)
            .mapNotNull { key ->
                val held = grants.store.examine(key) ?: return@mapNotNull null
                val live = held.version >= voidBelow(key.tenant, key.principalId) && held.value.isValidAt(now)
                key.principalId.takeIf { live && held.value.role == role && counts(held.value) }
            }.toSet()
    }

    private fun voidBelow(
        tenant: TenantId,
        principalId: String,
    ): Long = principals.peek(PrincipalKey(tenant, principalId))?.revocationVersion ?: Long.MIN_VALUE

    /** Whether a grant answers a role question at [level]; the structure and the consent are read once, lazily. */
    private fun counter(
        tenant: TenantId,
        level: ScopeLevel,
        entityRef: String?,
        deadline: Deadline,
    ): (RoleGrant) -> Boolean {
        val tenantRef = tenant.toString()
        return when (level) {
            ScopeLevel.INSTALLATION -> {
                { it.scope == GrantScope.INSTALLATION }
            }

            ScopeLevel.TENANT, ScopeLevel.GROUP -> {
                { it.scope == GrantScope.TENANT && it.scopeRef == tenantRef }
            }

            else -> {
                entityCounter(tenant, level, entityRef, deadline)
            }
        }
    }

    /** The entity-scoped levels: a grant counts when its scope covers [entityRef] (the ENTITY row of the matrix). */
    private fun entityCounter(
        tenant: TenantId,
        level: ScopeLevel,
        entityRef: String?,
        deadline: Deadline,
    ): (RoleGrant) -> Boolean {
        val tenantRef = tenant.toString()
        val groups by lazy { entityRef?.let { groupAncestors(tenant, it, deadline) }.orEmpty() }
        val consentLive by lazy {
            entityRef != null && consents.peek(ConsentKey(tenant, entityRef))?.isLiveAt(clock.instant()) == true
        }
        val clientEntity = level == ScopeLevel.CLIENT_ENTITY
        return { grant ->
            entityRef != null &&
                when (grant.scope) {
                    GrantScope.TENANT -> grant.scopeRef == tenantRef
                    GrantScope.GROUP -> grant.scopeRef != null && grant.scopeRef in groups
                    GrantScope.ENTITY -> grant.scopeRef == entityRef
                    GrantScope.CONSENT -> consentLive
                    GrantScope.CLIENT_ENTITY -> clientEntity && grant.scopeRef == entityRef
                    else -> false
                }
        }
    }

    /**
     * The GROUP nodes above [entityRef], following `parentRef` at most [MAX_STEPS] steps. A missing node ends the walk;
     * a longer chain or a loop gives no group at all.
     */
    private fun groupAncestors(
        tenant: TenantId,
        entityRef: String,
        deadline: Deadline,
    ): Set<String> {
        val replica = structure
        val groups = mutableSetOf<String>()
        val seen = mutableSetOf(entityRef)
        var node = replica?.let { nodeOf(it, tenant, entityRef, deadline) }
        var steps = 0
        var bounded = true
        while (replica != null && bounded) {
            val parent = node?.parentRef
            steps++
            bounded = parent == null || (steps <= MAX_STEPS && seen.add(parent))
            node = parent?.takeIf { bounded }?.let { nodeOf(replica, tenant, it, deadline) }
            if (node?.kind == StructureNodeKind.GROUP) groups += checkNotNull(parent)
            if (node == null) break
        }
        return if (bounded) groups else emptySet()
    }

    private fun nodeOf(
        replica: StructureReplica,
        tenant: TenantId,
        ref: String,
        deadline: Deadline,
    ): StructureNode? =
        when (val found = replica.node(tenant, ref, deadline)) {
            is Lookup.Local -> found.value
            is Lookup.Filled -> found.value
            is Lookup.LastKnown -> found.value
            else -> null
        }

    private companion object {
        const val MAX_STEPS = 16
    }
}
