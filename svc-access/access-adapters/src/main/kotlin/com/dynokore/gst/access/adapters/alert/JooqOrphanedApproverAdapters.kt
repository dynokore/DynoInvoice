package com.dynokore.gst.access.adapters.alert

import com.dynokore.gst.access.adapters.persistence.JooqGrantRepository
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.ORPHAN_ALERT_STATE
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.ROLE_GRANT
import com.dynokore.gst.access.application.alert.ActiveApprovers
import com.dynokore.gst.access.application.alert.HeldApprover
import com.dynokore.gst.access.application.alert.OrphanAlertState
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.domain.grant.ConsentCheck
import com.dynokore.gst.access.domain.grant.EntityMembership
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.ScopeLevel
import com.dynokore.gst.access.domain.grant.Target
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * TPL-11, ACC-001 (INV-REQ-895). Counts the ACTIVE grants of role `tax.approver` whose window contains the instant
 * and whose scope covers the entity. Covering is the domain's own `Scope.covers` (the rule `EntityDirectory` expands
 * scopes by), applied to the grants read through [JooqGrantRepository.toGrant]; there is no second implementation of
 * it. An entity the structure does not know is asked with the tenant alone and no group, so only tenant-wide and
 * entity-level grants can cover it. The DSLContext adds the bound tenant to the statement.
 */
class JooqActiveApprovers(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    clock: Clock,
    consent: ConsentCheck,
    private val directory: EntityDirectory,
) : ActiveApprovers {
    private val grants = JooqGrantRepository(dsl, tenants, clock, consent)

    override fun countFor(
        entity: EntityRef,
        at: Instant,
    ): Int {
        val membership = directory.membership(entity) ?: EntityMembership(TenantRef(tenants.current().toString()), null)
        val atOffset = at.atOffset(ZoneOffset.UTC)
        return dsl
            .selectFrom(ROLE_GRANT)
            .where(
                ROLE_GRANT.ROLE.eq(Role.TAX_APPROVER.wire),
                ROLE_GRANT.STATUS.eq(GrantStatus.ACTIVE.name),
                ROLE_GRANT.VALID_FROM.le(atOffset),
                ROLE_GRANT.VALID_UNTIL.isNull.or(ROLE_GRANT.VALID_UNTIL.gt(atOffset)),
            ).fetch()
            .mapNotNull { grants.toGrant(it) }
            .count { it.window.isValidAt(at) && it.scope.covers(Target(entity), membership) }
    }
}

/**
 * One existence query over `access.role_grant` of role `tax.approver`, any status: a grant whose scope reaches the
 * entity (tenant-wide or consent-derived, the entity's group, or the entity itself).
 */
class JooqHeldApprover(
    private val dsl: DSLContext,
    private val directory: EntityDirectory,
) : HeldApprover {
    override fun everHeld(entity: EntityRef): Boolean {
        val group = directory.membership(entity)?.group?.value
        val reaches =
            ROLE_GRANT.SCOPE_LEVEL
                .`in`(ScopeLevel.TENANT.name, ScopeLevel.CONSENT.name)
                .or(
                    ROLE_GRANT.SCOPE_LEVEL
                        .`in`(ScopeLevel.ENTITY.name, ScopeLevel.ENTITY_PERIOD.name)
                        .and(ROLE_GRANT.SCOPE_REF.eq(entity.value)),
                ).let { base ->
                    if (group == null) {
                        base
                    } else {
                        base.or(ROLE_GRANT.SCOPE_LEVEL.eq(ScopeLevel.GROUP.name).and(ROLE_GRANT.SCOPE_REF.eq(group)))
                    }
                }
        return dsl.fetchExists(
            dsl.selectOne().from(ROLE_GRANT).where(ROLE_GRANT.ROLE.eq(Role.TAX_APPROVER.wire), reaches),
        )
    }
}

/** The open ACC-001 state: one row per (tenant, entity); `tryOpen` is one INSERT ... ON CONFLICT DO NOTHING. */
class JooqOrphanAlertState(
    private val dsl: DSLContext,
    private val clock: Clock,
) : OrphanAlertState {
    override fun tryOpen(entity: EntityRef): Boolean =
        dsl
            .insertInto(ORPHAN_ALERT_STATE)
            .set(ORPHAN_ALERT_STATE.ENTITY_REF, entity.value)
            .set(ORPHAN_ALERT_STATE.OPENED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .onConflict(ORPHAN_ALERT_STATE.TENANT_ID, ORPHAN_ALERT_STATE.ENTITY_REF)
            .doNothing()
            .execute() == 1

    override fun close(entity: EntityRef) {
        dsl.deleteFrom(ORPHAN_ALERT_STATE).where(ORPHAN_ALERT_STATE.ENTITY_REF.eq(entity.value)).execute()
    }

    override fun openEntities(): Set<EntityRef> =
        dsl
            .select(ORPHAN_ALERT_STATE.ENTITY_REF)
            .from(ORPHAN_ALERT_STATE)
            .fetch { EntityRef(checkNotNull(it.value1())) }
            .toSet()
}

/**
 * Calls the claim function `access.claim_orphan_scan_tenants()` (owned by the NOLOGIN `access_sys`, executable by
 * `access_runtime` only) in its own transaction, under the platform binding the caller holds.
 */
class JooqOrphanScanTenants(
    private val dsl: DSLContext,
    private val tx: TransactionRunner,
) : () -> List<TenantId> {
    override fun invoke(): List<TenantId> =
        tx.inTransaction {
            val claim = DSL.function(DSL.name("access", FUNCTION), SQLDataType.UUID)
            dsl.select(claim).fetch { TenantId(checkNotNull(it.get(claim))) }
        }

    companion object {
        const val FUNCTION = "claim_orphan_scan_tenants"
    }
}
