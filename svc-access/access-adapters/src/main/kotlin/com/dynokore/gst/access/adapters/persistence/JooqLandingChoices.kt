package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.LANDING_CHOICE
import com.dynokore.gst.access.application.landing.LandingChoices
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.outboxandqueue.UuidV7
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset

/** TPL-11. One row per user; clearing sets the role to null, since the runtime login never deletes. */
class JooqLandingChoices(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : LandingChoices {
    override fun find(identity: IdentityRef): Role? =
        dsl
            .select(LANDING_CHOICE.ROLE)
            .from(LANDING_CHOICE)
            .where(LANDING_CHOICE.IDENTITY_REF.eq(identity.value))
            .fetchOne(LANDING_CHOICE.ROLE)
            ?.let { Role.ofWire(it) }

    override fun put(
        identity: IdentityRef,
        role: Role,
    ) {
        write(identity, role.wire)
    }

    override fun clear(identity: IdentityRef) {
        write(identity, null)
    }

    private fun write(
        identity: IdentityRef,
        role: String?,
    ) {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound: a change runs in a bound transaction" }
        dsl
            .insertInto(LANDING_CHOICE)
            .set(LANDING_CHOICE.ID, ids.next(clock.instant()))
            .set(LANDING_CHOICE.IDENTITY_REF, identity.value)
            .set(LANDING_CHOICE.ROLE, role)
            .set(LANDING_CHOICE.CREATED_AT, now)
            .set(LANDING_CHOICE.CREATED_BY, actor)
            .set(LANDING_CHOICE.UPDATED_AT, now)
            .set(LANDING_CHOICE.UPDATED_BY, actor)
            .set(LANDING_CHOICE.VERSION, 0L)
            .onConflict(LANDING_CHOICE.TENANT_ID, LANDING_CHOICE.IDENTITY_REF)
            .doUpdate()
            .set(LANDING_CHOICE.ROLE, role)
            .set(LANDING_CHOICE.UPDATED_AT, now)
            .set(LANDING_CHOICE.UPDATED_BY, actor)
            .set(LANDING_CHOICE.VERSION, LANDING_CHOICE.VERSION.plus(1))
            .execute()
    }
}
