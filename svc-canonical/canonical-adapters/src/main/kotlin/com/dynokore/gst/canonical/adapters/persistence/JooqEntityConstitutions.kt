package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.ENTITY_CONSTITUTION
import com.dynokore.gst.canonical.application.document.EntityConstitutions
import com.dynokore.gst.canonical.application.entity.EntityConstitutionFact
import com.dynokore.gst.canonical.application.entity.EntityConstitutionStore
import org.jooq.DSLContext
import java.util.UUID

/**
 * TPL-11. jOOQ only. The DSLContext is the one of the open `canonical` transaction; forced row-level security binds
 * every statement to the tenant of the session. `apply` is one upsert whose WHERE keeps the newer row, so a concurrent
 * apply cannot lose a higher version (event-carried state transfer, INV-Q-21383 A). No row is ever deleted.
 */
class JooqEntityConstitutions(
    private val dsl: DSLContext,
) : EntityConstitutionStore,
    EntityConstitutions {
    override fun apply(fact: EntityConstitutionFact) {
        dsl
            .insertInto(ENTITY_CONSTITUTION)
            .set(ENTITY_CONSTITUTION.TENANT_ID, fact.tenantId)
            .set(ENTITY_CONSTITUTION.ENTITY_REF, fact.entityRef)
            .set(ENTITY_CONSTITUTION.CONSTITUTION, fact.constitution)
            .set(ENTITY_CONSTITUTION.VERSION, fact.version)
            .onConflict(ENTITY_CONSTITUTION.TENANT_ID, ENTITY_CONSTITUTION.ENTITY_REF)
            .doUpdate()
            .set(ENTITY_CONSTITUTION.CONSTITUTION, fact.constitution)
            .set(ENTITY_CONSTITUTION.VERSION, fact.version)
            .where(ENTITY_CONSTITUTION.VERSION.lt(fact.version))
            .execute()
    }

    override fun constitutionOf(
        tenantId: UUID,
        entityRef: String,
    ): String? =
        dsl
            .select(ENTITY_CONSTITUTION.CONSTITUTION)
            .from(ENTITY_CONSTITUTION)
            .where(ENTITY_CONSTITUTION.TENANT_ID.eq(tenantId))
            .and(ENTITY_CONSTITUTION.ENTITY_REF.eq(entityRef))
            .fetchOne(ENTITY_CONSTITUTION.CONSTITUTION)
}
