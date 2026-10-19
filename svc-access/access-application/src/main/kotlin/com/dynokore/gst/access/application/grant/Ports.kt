package com.dynokore.gst.access.application.grant

import com.dynokore.gst.access.domain.grant.EntityMembership
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope

/**
 * Persistence of grants. Every read is live and runs under the bound tenant: the database policy, not the application,
 * restricts the rows (INV-REQ-866). Nothing here caches (INV-REQ-1214).
 */
interface GrantRepository {
    /** Every grant of [identity] in the bound tenant, any status. */
    fun findByIdentity(identity: IdentityRef): List<Grant>

    fun find(ref: GrantRef): Grant?

    /** The existing grant with this repeat key (identity, role, scope), whatever its status; API-15 repeat handling. */
    fun findByRepeatKey(
        identity: IdentityRef,
        roleWire: String,
        scope: Scope,
    ): Grant?

    fun insert(grant: Grant)

    /** Replaces [before] by [after]; throws the platform's StaleAggregate when [before] is no longer current. */
    fun update(
        before: Grant,
        after: Grant,
    )

    /** Locks the identity row of the bound tenant, creating it first when it does not exist (invitation, FR-AF.20). */
    fun lockIdentity(
        identity: IdentityRef,
        kind: GranteeKind,
    )

    /** The next value of access-svc's one monotonic authority sequence, drawn after the identity row is locked. */
    fun nextAuthorityVersion(): Long

    /** A fresh opaque grant reference. */
    fun nextGrantRef(): GrantRef
}

/** The structure of the tenant as access-svc needs it to expand tenant and group scopes (structure may be cached). */
interface EntityDirectory {
    fun membership(entity: EntityRef): EntityMembership?

    /**
     * Every entity of the bound tenant that [scope] covers now, for the per-entity grant records of AuthorityChanged.
     */
    fun entitiesCoveredBy(scope: Scope): Set<EntityRef>
}
