package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.sequences.AUTHORITY_VERSION_SEQ
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.IDENTITY
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.ROLE_GRANT
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.domain.grant.ConsentCheck
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantHeader
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GrantSource
import com.dynokore.gst.access.domain.grant.GrantState
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.GroupRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.PeriodRange
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.ScopeLevel
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.serviceruntime.persistence.StaleAggregate
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.Record
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * TPL-11. The DSLContext is service-runtime's, wired with the tenancy-jooq listeners, which add `tenant_id = <bound
 * tenant>`
 * to every statement below or refuse it; forced row-level security stays behind that (INV-REQ-866). Every read is live.
 *
 * [consent] answers a consent-derived scope; until the consent task lands (INV-T-4939) the wiring passes a check that
 * finds no live consent, so such a grant covers nothing (fail closed).
 */
class JooqGrantRepository(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val consent: ConsentCheck,
    private val ids: UuidV7 = UuidV7(),
) : GrantRepository {
    override fun findByIdentity(identity: IdentityRef): List<Grant> =
        dsl
            .selectFrom(ROLE_GRANT)
            .where(ROLE_GRANT.IDENTITY_REF.eq(identity.value))
            .orderBy(ROLE_GRANT.GRANT_REF)
            .fetch()
            .mapNotNull { toGrant(it) }

    override fun find(ref: GrantRef): Grant? =
        dsl
            .selectFrom(ROLE_GRANT)
            .where(ROLE_GRANT.GRANT_REF.eq(ref.value))
            .fetchOne()
            ?.let { toGrant(it) }

    override fun findByRepeatKey(
        identity: IdentityRef,
        roleWire: String,
        scope: Scope,
    ): Grant? {
        val columns = columnsOf(scope)
        return dsl
            .selectFrom(ROLE_GRANT)
            .where(
                ROLE_GRANT.IDENTITY_REF.eq(identity.value),
                ROLE_GRANT.ROLE.eq(roleWire),
                ROLE_GRANT.SCOPE_LEVEL.eq(columns.level),
                ROLE_GRANT.SCOPE_REF.eq(columns.ref),
                columns.periodFrom?.let { ROLE_GRANT.PERIOD_FROM.eq(it) } ?: ROLE_GRANT.PERIOD_FROM.isNull,
                columns.periodTo?.let { ROLE_GRANT.PERIOD_TO.eq(it) } ?: ROLE_GRANT.PERIOD_TO.isNull,
            ).orderBy(ROLE_GRANT.CREATED_AT.desc())
            .limit(1)
            .fetchOne()
            ?.let { toGrant(it) }
    }

    override fun insert(grant: Grant) {
        val columns = columnsOf(grant.scope)
        val now = now()
        val actor = actor()
        dsl
            .insertInto(ROLE_GRANT)
            .set(ROLE_GRANT.ID, ids.next(clock.instant()))
            .set(ROLE_GRANT.GRANT_REF, grant.ref.value)
            .set(ROLE_GRANT.IDENTITY_REF, grant.identity.value)
            .set(ROLE_GRANT.ROLE, grant.role.wire)
            .set(ROLE_GRANT.SCOPE_LEVEL, columns.level)
            .set(ROLE_GRANT.SCOPE_REF, columns.ref)
            .set(ROLE_GRANT.PERIOD_FROM, columns.periodFrom)
            .set(ROLE_GRANT.PERIOD_TO, columns.periodTo)
            .set(ROLE_GRANT.VALID_FROM, grant.window.from.atOffset(ZoneOffset.UTC))
            .set(ROLE_GRANT.VALID_UNTIL, grant.window.until?.atOffset(ZoneOffset.UTC))
            .set(ROLE_GRANT.STATUS, grant.status.name)
            .set(ROLE_GRANT.GRANT_SOURCE, grant.source.name)
            .set(ROLE_GRANT.CREATED_AT, now)
            .set(ROLE_GRANT.CREATED_BY, actor)
            .set(ROLE_GRANT.UPDATED_AT, now)
            .set(ROLE_GRANT.UPDATED_BY, actor)
            .set(ROLE_GRANT.VERSION, grant.version)
            .execute()
    }

    override fun update(
        before: Grant,
        after: Grant,
    ) {
        require(before.ref == after.ref) { "update changes one grant" }
        val updated =
            dsl
                .update(ROLE_GRANT)
                .set(ROLE_GRANT.STATUS, after.status.name)
                .set(ROLE_GRANT.UPDATED_AT, now())
                .set(ROLE_GRANT.UPDATED_BY, actor())
                .set(ROLE_GRANT.VERSION, after.version)
                .where(ROLE_GRANT.GRANT_REF.eq(before.ref.value), ROLE_GRANT.VERSION.eq(before.version))
                .execute()
        if (updated != 1) throw StaleAggregate("Grant", after.version)
    }

    override fun lockIdentity(
        identity: IdentityRef,
        kind: GranteeKind,
    ) {
        val now = now()
        val actor = actor()
        dsl
            .insertInto(IDENTITY)
            .set(IDENTITY.ID, ids.next(clock.instant()))
            .set(IDENTITY.IDENTITY_REF, identity.value)
            .set(IDENTITY.GRANTEE_KIND, kind.name)
            .set(IDENTITY.CREATED_AT, now)
            .set(IDENTITY.CREATED_BY, actor)
            .set(IDENTITY.UPDATED_AT, now)
            .set(IDENTITY.UPDATED_BY, actor)
            .set(IDENTITY.VERSION, 0L)
            .onConflict(IDENTITY.TENANT_ID, IDENTITY.IDENTITY_REF)
            .doNothing()
            .execute()
        dsl
            .selectFrom(IDENTITY)
            .where(IDENTITY.IDENTITY_REF.eq(identity.value))
            .forUpdate()
            .fetchOne()
    }

    override fun nextAuthorityVersion(): Long = dsl.nextval(AUTHORITY_VERSION_SEQ)

    override fun nextGrantRef(): GrantRef =
        GrantRef(
            "GRT-" +
                ids
                    .next(clock.instant())
                    .toString()
                    .replace("-", "")
                    .takeLast(REF_DIGITS),
        )

    private fun now(): OffsetDateTime = clock.instant().atOffset(ZoneOffset.UTC)

    private fun actor(): String =
        checkNotNull(tenants.currentActor()) { "no actor is bound: a grant change runs in a bound transaction" }

    private fun toGrant(row: Record): Grant? {
        // a role removed from the catalogue grants nothing
        val role = Role.ofWire(row.get(ROLE_GRANT.ROLE).column("role")) ?: return null
        val tenant = TenantRef(row.get(ROLE_GRANT.TENANT_ID).column("tenant_id").toString())
        val scope = scopeOf(row, tenant, consent)
        return Grant(
            header =
                GrantHeader(
                    GrantRef(row.get(ROLE_GRANT.GRANT_REF).column("grant_ref")),
                    tenant,
                    IdentityRef(row.get(ROLE_GRANT.IDENTITY_REF).column("identity_ref")),
                ),
            role = role,
            scope = scope,
            window =
                ValidityWindow(
                    row.get(ROLE_GRANT.VALID_FROM).column("valid_from").toInstant(),
                    row.get(ROLE_GRANT.VALID_UNTIL)?.toInstant(),
                ),
            state =
                GrantState(
                    GrantStatus.valueOf(row.get(ROLE_GRANT.STATUS).column("status")),
                    GrantSource.valueOf(row.get(ROLE_GRANT.GRANT_SOURCE).column("grant_source")),
                    row.get(ROLE_GRANT.VERSION).column("version"),
                ),
        )
    }

    private companion object {
        const val REF_DIGITS = 16
    }
}

private fun scopeOf(
    row: Record,
    tenant: TenantRef,
    consent: ConsentCheck,
): Scope {
    val scopeRef = row.get(ROLE_GRANT.SCOPE_REF).column("scope_ref")
    return when (ScopeLevel.valueOf(row.get(ROLE_GRANT.SCOPE_LEVEL).column("scope_level"))) {
        ScopeLevel.TENANT -> {
            Scope.Tenant(TenantRef(scopeRef))
        }

        ScopeLevel.GROUP -> {
            Scope.Group(GroupRef(scopeRef), tenant)
        }

        ScopeLevel.ENTITY -> {
            Scope.Entity(EntityRef(scopeRef))
        }

        ScopeLevel.ENTITY_PERIOD -> {
            Scope.EntityPeriod(
                EntityRef(scopeRef),
                PeriodRange(
                    row.get(ROLE_GRANT.PERIOD_FROM).column("period_from"),
                    row.get(ROLE_GRANT.PERIOD_TO).column("period_to"),
                ),
            )
        }

        ScopeLevel.CONSENT -> {
            Scope.ConsentDerived(TenantRef(scopeRef), consent)
        }
    }
}

private data class Columns(
    val level: String,
    val ref: String,
    val periodFrom: LocalDate?,
    val periodTo: LocalDate?,
)

private fun columnsOf(scope: Scope): Columns =
    when (scope) {
        is Scope.Tenant -> Columns(scope.level.name, scope.tenant.value, null, null)
        is Scope.Group -> Columns(scope.level.name, scope.group.value, null, null)
        is Scope.Entity -> Columns(scope.level.name, scope.entity.value, null, null)
        is Scope.EntityPeriod -> Columns(scope.level.name, scope.entity.value, scope.periods.from, scope.periods.to)
        is Scope.ConsentDerived -> Columns(scope.level.name, scope.tenant.value, null, null)
    }
