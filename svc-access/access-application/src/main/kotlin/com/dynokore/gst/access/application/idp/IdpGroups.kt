package com.dynokore.gst.access.application.idp

import com.dynokore.gst.access.application.grant.AuthorityChangePublisher
import com.dynokore.gst.access.application.grant.AuthorityRecords
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.separation.ConflictingPairPolicy
import com.dynokore.gst.access.application.separation.ConflictingRolePairs
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantHeader
import com.dynokore.gst.access.domain.grant.GrantSource
import com.dynokore.gst.access.domain.grant.GrantState
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.gst.access.domain.role.SeparationOfDuty
import com.dynokore.gst.access.domain.role.TenantGrantableRoles
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/** A directory group mapped to the grants of a role, a scope and a validity window (INV-REQ-892 behaviour 2). */
data class IdpGroupMapping(
    val ref: String,
    val idpGroup: String,
    val role: Role,
    val scope: Scope,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
) {
    /** The window of every grant this mapping makes: from the start of `validFrom` (UTC) to the start of `validTo`. */
    fun window(): ValidityWindow =
        ValidityWindow(
            validFrom.atStartOfDay(ZoneOffset.UTC).toInstant(),
            validTo?.atStartOfDay(ZoneOffset.UTC)?.toInstant(),
        )
}

/** Persistence of group mappings in the bound tenant. */
interface IdpGroupMappings {
    fun find(ref: String): IdpGroupMapping?

    /** The active mapping with this repeat key (group, role, scope). */
    fun findActive(
        idpGroup: String,
        role: Role,
        scope: Scope,
    ): IdpGroupMapping?

    fun listActive(): List<IdpGroupMapping>

    fun insert(
        idpGroup: String,
        role: Role,
        scope: Scope,
        validFrom: LocalDate,
        validTo: LocalDate?,
    ): IdpGroupMapping

    fun remove(ref: String)
}

/** The grants not yet revoked that a directory-group mapping made (INV-REQ-892 behaviour 3). */
fun interface MappedGrants {
    fun madeFrom(mappingRef: String): List<Grant>
}

/** The members of a directory group, as the identity provider of the tenant reports them now. */
fun interface DirectoryGroupMembers {
    fun members(idpGroup: String): Set<IdentityRef>
}

/** What group provisioning writes besides the grants, and the rules a mapped grant obeys like any other. */
class IdpProvisioningEffects(
    val publisher: AuthorityChangePublisher,
    val audit: GrantAudit,
    val catalogue: RoleCatalogue,
    val mapped: MappedGrants,
    val conflictingPairs: ConflictingRolePairs,
)

/**
 * Brings the grants made from one mapping in line with the members of its group (INV-REQ-892 behaviour 3): a member
 * without a live grant of the mapped role and scope gets one, a grant made from the mapping for a person who left the
 * group is revoked, each change publishes AuthorityChanged and is audited. A member whose grant would complete the
 * `security.officer` and `tenant.admin` pair, or a pair listed in VP-82, is not given it (the separation of duty holds
 * for every route, fail closed). Runs inside the caller's transaction.
 */
class GroupGrantReconciler(
    private val grants: GrantRepository,
    directory: EntityDirectory,
    matrix: PermissionMatrix,
    private val effects: IdpProvisioningEffects,
) {
    private val records = AuthorityRecords(directory, matrix)

    fun reconcile(
        actor: String,
        tenant: TenantRef,
        mapping: IdpGroupMapping,
        members: Set<IdentityRef>,
    ) {
        effects.mapped
            .madeFrom(mapping.ref)
            .filter { it.identity !in members }
            .forEach { revoke(actor, it) }
        members.sortedBy { it.value }.forEach { add(actor, tenant, mapping, it) }
    }

    private fun add(
        actor: String,
        tenant: TenantRef,
        mapping: IdpGroupMapping,
        identity: IdentityRef,
    ) {
        // INV-Q-12013 A: a stored mapping of an Operator role writes no grant, before the identity lock
        if (!TenantGrantableRoles.refuses(mapping.role)) provision(actor, tenant, mapping, identity)
    }

    private fun provision(
        actor: String,
        tenant: TenantRef,
        mapping: IdpGroupMapping,
        identity: IdentityRef,
    ) {
        grants.lockIdentity(identity, GranteeKind.STAFF)
        val existing = grants.findByRepeatKey(identity, mapping.role.wire, mapping.scope)
        if ((existing == null || existing.status == GrantStatus.REVOKED) && !completesPair(identity, mapping)) {
            issue(actor, tenant, mapping, identity)
        }
    }

    /** The built-in pair first, then the pairs of VP-82 (a failed read fails the run); both only withhold a grant. */
    private fun completesPair(
        identity: IdentityRef,
        mapping: IdpGroupMapping,
    ): Boolean {
        val held =
            grants
                .findByIdentity(identity)
                .filter { it.status != GrantStatus.REVOKED }
                .map { it.role }
                .toSet()
        return SeparationOfDuty.completesConflict(held, mapping.role) ||
            ConflictingPairPolicy.completesListedPair(effects.conflictingPairs.current(), held, mapping.role)
    }

    private fun issue(
        actor: String,
        tenant: TenantRef,
        mapping: IdpGroupMapping,
        identity: IdentityRef,
    ) {
        val grant =
            Grant.issue(
                GrantHeader(grants.nextGrantRef(), tenant, identity),
                mapping.role,
                mapping.scope,
                mapping.window(),
                GrantState(GrantStatus.ACTIVE, GrantSource.DIRECTORY_GROUPS, 0, mapping.ref),
            )
        grants.insert(grant)
        effects.publisher.publish(
            records.forChange(tenant, identity, grant, grants.findByIdentity(identity), grants::nextAuthorityVersion),
        )
        audit(actor, "idpGroupGrant", grant, null)
    }

    private fun revoke(
        actor: String,
        stale: Grant,
    ) {
        grants.lockIdentity(stale.identity, GranteeKind.STAFF)
        val current = grants.find(stale.ref) ?: return
        val after = current.revoked()
        if (after === current) return
        grants.update(current, after)
        effects.publisher.publish(
            records.forChange(
                after.tenant,
                after.identity,
                after,
                grants.findByIdentity(after.identity),
                grants::nextAuthorityVersion,
            ),
        )
        audit(actor, "idpGroupRevoke", current, """{"status":"${current.status}","role":"${current.role.wire}"}""")
    }

    private fun audit(
        actor: String,
        action: String,
        grant: Grant,
        prior: String?,
    ) = effects.audit.record(
        GrantAuditEntry(
            actor,
            action,
            "${grant.scope.level}:${AuthorityRecords.scopeRefOf(grant.scope)}",
            "mapping ${grant.sourceRef}",
            prior,
        ),
    )
}

sealed interface MapIdpGroupOutcome {
    data class Mapped(
        val mapping: IdpGroupMapping,
        val repeated: Boolean,
    ) : MapIdpGroupOutcome

    data class UnknownRole(
        val roleWire: String,
    ) : MapIdpGroupOutcome

    data object ScopeMissing : MapIdpGroupOutcome

    /** `validTo` is not after `validFrom`. */
    data object WindowInvalid : MapIdpGroupOutcome

    /** The role is one of the six Operator roles, which are never mapped in a customer tenant (INV-Q-9069 A). */
    data object OperatorRoleRefused : MapIdpGroupOutcome
}

/** What the caller asks `mapIdpGroup` for, before any rule is applied. */
data class MapIdpGroupCommand(
    val actor: String,
    val tenant: TenantRef,
    val idpGroup: String,
    val roleWire: String,
    val scope: Scope?,
    val validFrom: LocalDate?,
    val validTo: LocalDate?,
)

/** The collaborators the three group use cases share. */
class IdpGroupCollaborators(
    val tx: TransactionRunner,
    val mappings: IdpGroupMappings,
    val members: DirectoryGroupMembers,
    val clock: Clock,
)

/**
 * `mapIdpGroup` (INV-REQ-892, INV-REQ-1263): stores the mapping (a repeat of the same group, role and scope returns
 * the original) and provisions the grants of the group's current members.
 */
class MapIdpGroupUseCase private constructor(
    private val shared: IdpGroupCollaborators,
    private val reconciler: GroupGrantReconciler,
    private val catalogue: RoleCatalogue,
) {
    constructor(
        shared: IdpGroupCollaborators,
        grants: GrantRepository,
        directory: EntityDirectory,
        matrix: PermissionMatrix,
        effects: IdpProvisioningEffects,
    ) : this(shared, GroupGrantReconciler(grants, directory, matrix, effects), effects.catalogue)

    fun handle(command: MapIdpGroupCommand): MapIdpGroupOutcome {
        val role = Role.ofWire(command.roleWire)
        val from = command.validFrom ?: LocalDate.now(shared.clock.withZone(ZoneOffset.UTC))
        val validTo = command.validTo
        return when {
            command.scope == null -> {
                MapIdpGroupOutcome.ScopeMissing
            }

            role == null || !catalogue.contains(role) -> {
                MapIdpGroupOutcome.UnknownRole(command.roleWire)
            }

            TenantGrantableRoles.refuses(role) -> {
                MapIdpGroupOutcome.OperatorRoleRefused
            }

            validTo != null && !validTo.isAfter(from) -> {
                MapIdpGroupOutcome.WindowInvalid
            }

            else -> {
                shared.tx.inTransaction {
                    map(command, IdpGroupMapping("", command.idpGroup, role, command.scope, from, validTo))
                }
            }
        }
    }

    /** [wanted] carries the resolved role, scope and window of the command under an empty reference. */
    private fun map(
        command: MapIdpGroupCommand,
        wanted: IdpGroupMapping,
    ): MapIdpGroupOutcome {
        val existing = shared.mappings.findActive(wanted.idpGroup, wanted.role, wanted.scope)
        if (existing != null) return MapIdpGroupOutcome.Mapped(existing, repeated = true)
        val mapping =
            shared.mappings.insert(
                wanted.idpGroup,
                wanted.role,
                wanted.scope,
                wanted.validFrom,
                wanted.validTo,
            )
        reconciler.reconcile(command.actor, command.tenant, mapping, shared.members.members(wanted.idpGroup))
        return MapIdpGroupOutcome.Mapped(mapping, repeated = false)
    }
}

sealed interface UnmapIdpGroupOutcome {
    data object Removed : UnmapIdpGroupOutcome

    /** Already removed: a no-op (API-15). */
    data object NotFound : UnmapIdpGroupOutcome
}

/** `unmapIdpGroup`: removes the mapping and revokes every grant that was made from it. */
class UnmapIdpGroupUseCase private constructor(
    private val shared: IdpGroupCollaborators,
    private val reconciler: GroupGrantReconciler,
) {
    constructor(
        shared: IdpGroupCollaborators,
        grants: GrantRepository,
        directory: EntityDirectory,
        matrix: PermissionMatrix,
        effects: IdpProvisioningEffects,
    ) : this(shared, GroupGrantReconciler(grants, directory, matrix, effects))

    fun handle(
        actor: String,
        tenant: TenantRef,
        mappingRef: String,
    ): UnmapIdpGroupOutcome =
        shared.tx.inTransaction {
            val mapping = shared.mappings.find(mappingRef) ?: return@inTransaction UnmapIdpGroupOutcome.NotFound
            reconciler.reconcile(actor, tenant, mapping, emptySet())
            shared.mappings.remove(mappingRef)
            UnmapIdpGroupOutcome.Removed
        }
}

/**
 * The synchronisation (INV-Q-8665 A): an internal use case of the service, with no contract operation. For the bound
 * tenant it reads the members of every mapped group, adds the grants of members that lack one and removes the grants
 * made from a mapping for people no longer in the group, publishing AuthorityChanged for each change.
 */
class SyncIdpGroupsUseCase private constructor(
    private val shared: IdpGroupCollaborators,
    private val reconciler: GroupGrantReconciler,
) {
    constructor(
        shared: IdpGroupCollaborators,
        grants: GrantRepository,
        directory: EntityDirectory,
        matrix: PermissionMatrix,
        effects: IdpProvisioningEffects,
    ) : this(shared, GroupGrantReconciler(grants, directory, matrix, effects))

    /**
     * Synchronises every active mapping of the bound tenant; each mapping is its own transaction. A mapping whose
     * group cannot be read changes nothing (no grant is removed on a failed read); the run goes on with the other
     * mappings and then reports how many failed.
     */
    fun run(
        actor: String,
        tenant: TenantRef,
    ) {
        val mappings = shared.tx.inTransaction { shared.mappings.listActive() }
        val failed =
            mappings.count { listed ->
                runCatching {
                    shared.tx.inTransaction {
                        // a mapping removed since the listing is not synchronised
                        val mapping = shared.mappings.find(listed.ref)
                        if (mapping != null) {
                            reconciler.reconcile(actor, tenant, mapping, shared.members.members(mapping.idpGroup))
                        }
                    }
                }.isFailure
            }
        check(failed == 0) { "$failed of ${mappings.size} group mappings could not be synchronised" }
    }
}
