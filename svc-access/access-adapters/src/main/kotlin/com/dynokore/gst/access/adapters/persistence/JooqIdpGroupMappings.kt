package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.IDP_GROUP_MAPPING
import com.dynokore.gst.access.application.idp.IdpGroupMapping
import com.dynokore.gst.access.application.idp.IdpGroupMappings
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.ScopeLevel
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.Record
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * TPL-11. Group mappings of the bound tenant (the DSLContext's listeners add the tenant to every statement; forced
 * row-level security stays behind). Only tenant and entity scopes are stored: those are the scopes the contract
 * publishes a grammar for.
 */
class JooqIdpGroupMappings(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : IdpGroupMappings {
    override fun find(ref: String): IdpGroupMapping? =
        dsl
            .selectFrom(IDP_GROUP_MAPPING)
            .where(IDP_GROUP_MAPPING.MAPPING_REF.eq(ref), IDP_GROUP_MAPPING.STATUS.eq(ACTIVE))
            .fetchOne()
            ?.let { toMapping(it) }

    override fun findActive(
        idpGroup: String,
        role: Role,
        scope: Scope,
    ): IdpGroupMapping? {
        val (level, ref) = scopeColumns(scope)
        return dsl
            .selectFrom(IDP_GROUP_MAPPING)
            .where(
                IDP_GROUP_MAPPING.IDP_GROUP.eq(idpGroup),
                IDP_GROUP_MAPPING.ROLE.eq(role.wire),
                IDP_GROUP_MAPPING.SCOPE_LEVEL.eq(level),
                IDP_GROUP_MAPPING.SCOPE_REF.eq(ref),
                IDP_GROUP_MAPPING.STATUS.eq(ACTIVE),
            ).fetchOne()
            ?.let { toMapping(it) }
    }

    override fun listActive(): List<IdpGroupMapping> =
        dsl
            .selectFrom(IDP_GROUP_MAPPING)
            .where(IDP_GROUP_MAPPING.STATUS.eq(ACTIVE))
            .orderBy(IDP_GROUP_MAPPING.MAPPING_REF)
            .fetch()
            .mapNotNull { toMapping(it) }

    override fun insert(
        idpGroup: String,
        role: Role,
        scope: Scope,
        validFrom: LocalDate,
        validTo: LocalDate?,
    ): IdpGroupMapping {
        val (level, scopeRef) = scopeColumns(scope)
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound: a change runs in a bound transaction" }
        val ref =
            "IDPM-" +
                ids
                    .next(clock.instant())
                    .toString()
                    .replace("-", "")
                    .takeLast(REF_DIGITS)
        dsl
            .insertInto(IDP_GROUP_MAPPING)
            .set(IDP_GROUP_MAPPING.ID, ids.next(clock.instant()))
            .set(IDP_GROUP_MAPPING.MAPPING_REF, ref)
            .set(IDP_GROUP_MAPPING.IDP_GROUP, idpGroup)
            .set(IDP_GROUP_MAPPING.ROLE, role.wire)
            .set(IDP_GROUP_MAPPING.SCOPE_LEVEL, level)
            .set(IDP_GROUP_MAPPING.SCOPE_REF, scopeRef)
            .set(IDP_GROUP_MAPPING.VALID_FROM, validFrom)
            .set(IDP_GROUP_MAPPING.VALID_TO, validTo)
            .set(IDP_GROUP_MAPPING.STATUS, ACTIVE)
            .set(IDP_GROUP_MAPPING.CREATED_AT, now)
            .set(IDP_GROUP_MAPPING.CREATED_BY, actor)
            .set(IDP_GROUP_MAPPING.UPDATED_AT, now)
            .set(IDP_GROUP_MAPPING.UPDATED_BY, actor)
            .set(IDP_GROUP_MAPPING.VERSION, 0L)
            .execute()
        return checkNotNull(find(ref)) { "the mapping just written is not readable" }
    }

    override fun remove(ref: String) {
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound: a change runs in a bound transaction" }
        dsl
            .update(IDP_GROUP_MAPPING)
            .set(IDP_GROUP_MAPPING.STATUS, REMOVED)
            .set(IDP_GROUP_MAPPING.UPDATED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .set(IDP_GROUP_MAPPING.UPDATED_BY, actor)
            .set(IDP_GROUP_MAPPING.VERSION, IDP_GROUP_MAPPING.VERSION.plus(1))
            .where(IDP_GROUP_MAPPING.MAPPING_REF.eq(ref))
            .execute()
    }

    private fun toMapping(row: Record): IdpGroupMapping? {
        // a role removed from the catalogue maps nothing
        val role = Role.ofWire(row.get(IDP_GROUP_MAPPING.ROLE).column("role")) ?: return null
        val scopeRef = row.get(IDP_GROUP_MAPPING.SCOPE_REF).column("scope_ref")
        val scope =
            when (ScopeLevel.valueOf(row.get(IDP_GROUP_MAPPING.SCOPE_LEVEL).column("scope_level"))) {
                ScopeLevel.TENANT -> Scope.Tenant(TenantRef(scopeRef))
                ScopeLevel.ENTITY -> Scope.Entity(EntityRef(scopeRef))
                else -> error("a mapping holds a tenant or an entity scope only")
            }
        return IdpGroupMapping(
            ref = row.get(IDP_GROUP_MAPPING.MAPPING_REF).column("mapping_ref"),
            idpGroup = row.get(IDP_GROUP_MAPPING.IDP_GROUP).column("idp_group"),
            role = role,
            scope = scope,
            validFrom = row.get(IDP_GROUP_MAPPING.VALID_FROM).column("valid_from"),
            validTo = row.get(IDP_GROUP_MAPPING.VALID_TO),
        )
    }

    private fun scopeColumns(scope: Scope): Pair<String, String> =
        when (scope) {
            is Scope.Tenant -> scope.level.name to scope.tenant.value
            is Scope.Entity -> scope.level.name to scope.entity.value
            else -> throw IllegalArgumentException("a group mapping holds a tenant or an entity scope only")
        }

    private companion object {
        const val ACTIVE = "ACTIVE"
        const val REMOVED = "REMOVED"
        const val REF_DIGITS = 8
    }
}
