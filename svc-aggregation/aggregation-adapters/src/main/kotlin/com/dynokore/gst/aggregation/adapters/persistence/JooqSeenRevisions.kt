package com.dynokore.gst.aggregation.adapters.persistence

import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.SeenRevision.Companion.SEEN_REVISION
import com.dynokore.gst.aggregation.application.SeenRevisions
import com.dynokore.gst.aggregation.application.StoredAggregate
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

/** The revisions seen without a stored aggregate (INV-REQ-434 item 4, zero difference). */
class JooqSeenRevisions(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val runner: TransactionRunner? = null,
) : SeenRevisions {
    override fun recordSeen(
        compared: StoredAggregate,
        sourceRevision: Long,
    ) {
        checkNotNull(runner) { "recording needs a transaction runner" }.inTransaction {
            dsl
                .insertInto(SEEN_REVISION)
                .set(SEEN_REVISION.TENANT_ID, tenants.current().value)
                .set(SEEN_REVISION.ENTITY_REF, compared.aggregate.entityRef)
                .set(SEEN_REVISION.SOURCE_KEY, compared.aggregate.sourceKey)
                .set(SEEN_REVISION.SOURCE_REVISION, sourceRevision)
                .set(SEEN_REVISION.AGGREGATE_ID, compared.aggregateId)
                .onConflictDoNothing()
                .execute()
        }
    }

    /** The id of the aggregate the revision was compared with; call inside a transaction. */
    internal fun aggregateId(
        entityRef: String,
        sourceKey: String,
        sourceRevision: Long,
    ): UUID? =
        dsl
            .select(SEEN_REVISION.AGGREGATE_ID)
            .from(SEEN_REVISION)
            .where(SEEN_REVISION.TENANT_ID.eq(tenants.current().value))
            .and(SEEN_REVISION.ENTITY_REF.eq(entityRef))
            .and(SEEN_REVISION.SOURCE_KEY.eq(sourceKey))
            .and(SEEN_REVISION.SOURCE_REVISION.eq(sourceRevision))
            .fetchOne()
            ?.value1()

    /** The highest revision seen for the key; call inside a transaction. */
    internal fun highest(
        entityRef: String,
        sourceKey: String,
    ): Long? =
        dsl
            .select(DSL.max(SEEN_REVISION.SOURCE_REVISION))
            .from(SEEN_REVISION)
            .where(SEEN_REVISION.TENANT_ID.eq(tenants.current().value))
            .and(SEEN_REVISION.ENTITY_REF.eq(entityRef))
            .and(SEEN_REVISION.SOURCE_KEY.eq(sourceKey))
            .fetchOne()
            ?.value1()
}
