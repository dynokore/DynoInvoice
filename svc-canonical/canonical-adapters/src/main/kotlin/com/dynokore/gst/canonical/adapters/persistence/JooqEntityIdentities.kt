package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.ENTITY_IDENTITY
import org.jooq.DSLContext
import java.util.UUID

/** The entity id carried by one EntityProfileChanged event of [version] (the node's structureVersion). */
data class EntityIdentity(
    val tenantId: UUID,
    val entityRef: String,
    val entityId: UUID,
    val version: Long,
)

/**
 * TPL-11. jOOQ only. The DSLContext is the one of the open `canonical` transaction; forced row-level security binds
 * every statement to the tenant of the session. The row is replaced only by a higher event version (idempotent,
 * version-guarded). Nothing is ever deleted (INV-Q-21451 A, event-carried state transfer).
 */
class JooqEntityIdentities(
    private val dsl: DSLContext,
) {
    /** True when a row was inserted or replaced; false when the version is not above the stored one. */
    fun apply(identity: EntityIdentity): Boolean =
        dsl
            .insertInto(ENTITY_IDENTITY)
            .set(ENTITY_IDENTITY.TENANT_ID, identity.tenantId)
            .set(ENTITY_IDENTITY.ENTITY_REF, identity.entityRef)
            .set(ENTITY_IDENTITY.ENTITY_ID, identity.entityId)
            .set(ENTITY_IDENTITY.VERSION, identity.version)
            .onConflict(ENTITY_IDENTITY.TENANT_ID, ENTITY_IDENTITY.ENTITY_REF)
            .doUpdate()
            .set(ENTITY_IDENTITY.ENTITY_ID, identity.entityId)
            .set(ENTITY_IDENTITY.VERSION, identity.version)
            .where(ENTITY_IDENTITY.VERSION.lt(identity.version))
            .execute() > 0

    /** The entity id stored for ([tenantId], [entityRef]), or null when none. */
    fun entityIdOf(
        tenantId: UUID,
        entityRef: String,
    ): UUID? =
        dsl
            .select(ENTITY_IDENTITY.ENTITY_ID)
            .from(ENTITY_IDENTITY)
            .where(ENTITY_IDENTITY.TENANT_ID.eq(tenantId))
            .and(ENTITY_IDENTITY.ENTITY_REF.eq(entityRef))
            .fetchOne(ENTITY_IDENTITY.ENTITY_ID)
}
