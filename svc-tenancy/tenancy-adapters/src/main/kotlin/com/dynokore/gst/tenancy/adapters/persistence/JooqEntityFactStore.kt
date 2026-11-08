package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY_FACT
import com.dynokore.gst.tenancy.application.questionnaire.EntityFactStore
import com.dynokore.gst.tenancy.application.questionnaire.EntityFactValues
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** The entity facts of the bound tenant (jOOQ only, CS-18); forced RLS hides every other tenant's rows. */
class JooqEntityFactStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : EntityFactStore {
    override fun entityExists(entityRef: String): Boolean =
        dsl.fetchExists(ENTITY, ENTITY.TENANT_ID.eq(tenants.current().value).and(ENTITY.REF.eq(entityRef)))

    override fun merge(
        entityRef: String,
        facts: EntityFactValues,
        actor: String,
    ) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        dsl
            .insertInto(ENTITY_FACT)
            .set(ENTITY_FACT.TENANT_ID, tenants.current().value)
            .set(ENTITY_FACT.ENTITY_REF, entityRef)
            .set(ENTITY_FACT.RECORDS_PURCHASES, facts.recordsPurchases)
            .set(ENTITY_FACT.HAS_FOREIGN_CURRENCY_DOCUMENTS, facts.hasForeignCurrencyDocuments)
            .set(ENTITY_FACT.CLAIMS_INPUT_TAX_ON_POSTING_BASIS, facts.claimsInputTaxOnPostingBasis)
            .set(ENTITY_FACT.RECORDED_BY, actor)
            .set(ENTITY_FACT.RECORDED_AT, now)
            .onConflict(ENTITY_FACT.TENANT_ID, ENTITY_FACT.ENTITY_REF)
            .doUpdate()
            .set(
                ENTITY_FACT.RECORDS_PURCHASES,
                DSL.coalesce(DSL.excluded(ENTITY_FACT.RECORDS_PURCHASES), ENTITY_FACT.RECORDS_PURCHASES),
            ).set(
                ENTITY_FACT.HAS_FOREIGN_CURRENCY_DOCUMENTS,
                DSL.coalesce(
                    DSL.excluded(ENTITY_FACT.HAS_FOREIGN_CURRENCY_DOCUMENTS),
                    ENTITY_FACT.HAS_FOREIGN_CURRENCY_DOCUMENTS,
                ),
            ).set(
                ENTITY_FACT.CLAIMS_INPUT_TAX_ON_POSTING_BASIS,
                DSL.coalesce(
                    DSL.excluded(ENTITY_FACT.CLAIMS_INPUT_TAX_ON_POSTING_BASIS),
                    ENTITY_FACT.CLAIMS_INPUT_TAX_ON_POSTING_BASIS,
                ),
            ).set(ENTITY_FACT.RECORDED_BY, actor)
            .set(ENTITY_FACT.RECORDED_AT, now)
            .execute()
    }

    override fun find(entityRef: String): EntityFactValues? =
        dsl
            .selectFrom(ENTITY_FACT)
            .where(ENTITY_FACT.ENTITY_REF.eq(entityRef))
            .fetchOne()
            ?.let {
                EntityFactValues(
                    it.recordsPurchases,
                    it.hasForeignCurrencyDocuments,
                    it.claimsInputTaxOnPostingBasis,
                )
            }
}
