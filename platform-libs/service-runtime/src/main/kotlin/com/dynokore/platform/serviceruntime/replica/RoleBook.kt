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
@Suppress("TooManyFunctions") // role answers, holders and decision candidates share one store (INV-Q-8972 A)
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

    /** Drops every role grant, the role index and the noted tombstones (a higher incarnation, INV-T-9262). */
    fun clear() {
        grants.clear()
        index.clear()
        tombstones.clear()
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

    /** A role grant that covers a request by scope and validity; [voided] when a principal record voids it. */
    internal class Candidate(
        val grant: RoleGrant,
        val voided: Boolean,
    )

    /**
     * The grants of [principalId] that cover a request at [level] by scope and validity window, the voided ones
     * marked. It is the ENTITY row of [rolesAt] at the entity levels, except that a CONSENT grant is returned whether
     * or not the consent is live (the replica reads the consent per call) and an ENTITY_PERIOD grant is returned when
     * its span encloses [period] on [entityRef] (a null period matches none). Reads no lag; the caller checks it.
     */
    fun candidates(
        tenant: TenantId,
        principalId: String,
        level: ScopeLevel,
        entityRef: String?,
        period: DateSpan?,
        deadline: Deadline,
    ): List<Candidate> {
        val owner = if (level == ScopeLevel.INSTALLATION) TenantId.PLATFORM else tenant
        val voidBelow = voidBelow(owner, principalId)
        val now = clock.instant()
        val counts = counter(tenant, level, entityRef, deadline, liveConsentOnly = false)
        val entityLevel = level.entityScoped
        return grants.store
            .heldEntries(owner to principalId) { true }
            .values
            .filter {
                it.value.isValidAt(now) &&
                    (counts(it.value) || coversPeriod(it.value, entityLevel, entityRef, period))
            }.map { Candidate(it.value, it.version < voidBelow) }
    }

    private fun coversPeriod(
        grant: RoleGrant,
        entityLevel: Boolean,
        entityRef: String?,
        period: DateSpan?,
    ): Boolean {
        val from = grant.periodFrom
        val to = grant.periodTo
        return entityLevel && grant.scope == GrantScope.ENTITY_PERIOD && entityRef != null &&
            grant.scopeRef == entityRef &&
            period != null && from != null && to != null && !to.isBefore(from) && DateSpan(from, to).encloses(period)
    }

    /** The entities [principalId] holds a valid role grant on in [tenant]; see [AuthorityReplica.heldEntities]. */
    fun heldEntities(
        tenant: TenantId,
        principalId: String,
    ): Set<String> {
        val voidBelow = voidBelow(tenant, principalId)
        val now = clock.instant()
        val tenantRef = tenant.toString()
        val live =
            grants.store
                .heldEntries(tenant to principalId) { true }
                .values
                .filter { it.version >= voidBelow && it.value.isValidAt(now) }
                .map { it.value }
        val held = mutableSetOf<String>()
        live.filter { it.scopeRef != null }.forEach { grant ->
            val ref = checkNotNull(grant.scopeRef)
            val direct = grant.scope == GrantScope.ENTITY || grant.scope == GrantScope.ENTITY_PERIOD
            val consented =
                grant.scope == GrantScope.CONSENT && consents.peek(ConsentKey(tenant, ref))?.isLiveAt(now) == true
            if (direct || consented) held += ref
        }
        val replica = structure
        if (replica != null) {
            val groupRefs = live.filter { it.scope == GrantScope.GROUP }.mapNotNullTo(mutableSetOf()) { it.scopeRef }
            if (live.any { it.scope == GrantScope.TENANT && it.scopeRef == tenantRef }) {
                held += replica.entitiesOf(tenant).keys
            } else if (groupRefs.isNotEmpty()) {
                held += replica.entitiesIn(tenant, groupRefs, MAX_STEPS)
            }
        }
        return held
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
        liveConsentOnly: Boolean = true,
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
                entityCounter(tenant, level, entityRef, deadline, liveConsentOnly)
            }
        }
    }

    private fun consentLive(
        tenant: TenantId,
        entityRef: String?,
    ): Boolean = entityRef != null && consents.peek(ConsentKey(tenant, entityRef))?.isLiveAt(clock.instant()) == true

    /** The entity-scoped levels: a grant counts when its scope covers [entityRef] (the ENTITY row of the matrix). */
    private fun entityCounter(
        tenant: TenantId,
        level: ScopeLevel,
        entityRef: String?,
        deadline: Deadline,
        liveConsentOnly: Boolean,
    ): (RoleGrant) -> Boolean {
        val tenantRef = tenant.toString()
        val groups by lazy { entityRef?.let { groupAncestors(tenant, it, deadline) }.orEmpty() }
        val consentCounts by lazy { !liveConsentOnly || consentLive(tenant, entityRef) }
        val clientEntity = level == ScopeLevel.CLIENT_ENTITY
        return { grant ->
            entityRef != null &&
                when (grant.scope) {
                    GrantScope.TENANT -> grant.scopeRef == tenantRef
                    GrantScope.GROUP -> grant.scopeRef != null && grant.scopeRef in groups
                    GrantScope.ENTITY -> grant.scopeRef == entityRef
                    GrantScope.CONSENT -> consentCounts
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
