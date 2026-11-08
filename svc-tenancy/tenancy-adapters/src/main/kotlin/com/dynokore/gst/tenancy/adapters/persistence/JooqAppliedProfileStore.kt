package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.APPLIED_PROFILE_ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.APPLIED_PROFILE_TENANT
import com.dynokore.gst.tenancy.application.profile.AppliedProfileStore
import com.dynokore.gst.tenancy.application.profile.AppliedProfileValues
import com.dynokore.gst.tenancy.application.profile.ProfileScope
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The applied operating profile records of the bound tenant (jOOQ only, CS-18): one table per scope, since a per-entity
 * table keeps a non-nullable entity reference (INV-AC-2041); the tenant table is keyed by tenant_id
 * alone (INV-Q-9833 A). Forced RLS hides every other tenant's rows.
 */
class JooqAppliedProfileStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : AppliedProfileStore {
    override fun put(
        scope: ProfileScope,
        entityId: UUID?,
        profileRef: String,
        profileVersion: Long,
    ) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val tenant = tenants.current().value
        when (scope) {
            ProfileScope.TENANT -> {
                dsl
                    .insertInto(APPLIED_PROFILE_TENANT)
                    .set(APPLIED_PROFILE_TENANT.ID, UUID.randomUUID())
                    .set(APPLIED_PROFILE_TENANT.TENANT_ID, tenant)
                    .set(APPLIED_PROFILE_TENANT.PROFILE_REF, profileRef)
                    .set(APPLIED_PROFILE_TENANT.PROFILE_VERSION, profileVersion)
                    .set(APPLIED_PROFILE_TENANT.RECORDED_AT, now)
                    .onConflict(APPLIED_PROFILE_TENANT.TENANT_ID)
                    .doUpdate()
                    .set(APPLIED_PROFILE_TENANT.PROFILE_REF, profileRef)
                    .set(APPLIED_PROFILE_TENANT.PROFILE_VERSION, profileVersion)
                    .set(APPLIED_PROFILE_TENANT.RECORDED_AT, now)
                    .execute()
            }

            ProfileScope.ENTITY -> {
                dsl
                    .insertInto(APPLIED_PROFILE_ENTITY)
                    .set(APPLIED_PROFILE_ENTITY.ID, UUID.randomUUID())
                    .set(APPLIED_PROFILE_ENTITY.TENANT_ID, tenant)
                    .set(APPLIED_PROFILE_ENTITY.ENTITY_ID, checkNotNull(entityId))
                    .set(APPLIED_PROFILE_ENTITY.PROFILE_REF, profileRef)
                    .set(APPLIED_PROFILE_ENTITY.PROFILE_VERSION, profileVersion)
                    .set(APPLIED_PROFILE_ENTITY.RECORDED_AT, now)
                    .onConflict(APPLIED_PROFILE_ENTITY.TENANT_ID, APPLIED_PROFILE_ENTITY.ENTITY_ID)
                    .doUpdate()
                    .set(APPLIED_PROFILE_ENTITY.PROFILE_REF, profileRef)
                    .set(APPLIED_PROFILE_ENTITY.PROFILE_VERSION, profileVersion)
                    .set(APPLIED_PROFILE_ENTITY.RECORDED_AT, now)
                    .execute()
            }
        }
    }

    override fun find(
        scope: ProfileScope,
        entityId: UUID?,
    ): AppliedProfileValues? =
        when (scope) {
            ProfileScope.TENANT -> {
                dsl.selectFrom(APPLIED_PROFILE_TENANT).fetchOne()?.let {
                    AppliedProfileValues(
                        it.profileRef.column("profile_ref"),
                        it.profileVersion.column("profile_version"),
                    )
                }
            }

            ProfileScope.ENTITY -> {
                dsl
                    .selectFrom(APPLIED_PROFILE_ENTITY)
                    .where(APPLIED_PROFILE_ENTITY.ENTITY_ID.eq(checkNotNull(entityId)))
                    .fetchOne()
                    ?.let {
                        AppliedProfileValues(
                            it.profileRef.column("profile_ref"),
                            it.profileVersion.column("profile_version"),
                        )
                    }
            }
        }
}
