package com.dynokore.gst.aggregation.adapters.persistence

import com.dynokore.gst.aggregation.adapters.persistence.jooq.tables.Aggregate.Companion.AGGREGATE
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

    override fun comparedWith(
        entityRef: String,
        sourceKey: String,
        excluding: UUID,
    ): StoredAggregate? =
        checkNotNull(runner) { "reading needs a transaction runner" }.inTransaction {
            dsl
                .selectFrom(AGGREGATE)
                .where(AGGREGATE.TENANT_ID.eq(tenants.current().value))
                .and(AGGREGATE.ENTITY_REF.eq(entityRef))
                .and(AGGREGATE.SOURCE_KEY.eq(sourceKey))
                .and(AGGREGATE.SUPERSEDED_BY.isNull)
                .and(AGGREGATE.AGGREGATE_ID.ne(excluding))
                .orderBy(AGGREGATE.SOURCE_REVISION.desc())
                .limit(1)
                .fetchOne()
                ?.let { JooqAggregateStore(dsl, checkNotNull(runner), tenants).stored(it) }
        }

    override fun supersedeUnchanged(
        old: StoredAggregate,
        compared: StoredAggregate,
        sourceRevision: Long,
    ) {
        checkNotNull(runner) { "reading needs a transaction runner" }.inTransaction {
            val marked =
                dsl
                    .update(AGGREGATE)
                    .set(AGGREGATE.SUPERSEDED_BY, compared.aggregateId)
                    .where(AGGREGATE.TENANT_ID.eq(tenants.current().value))
                    .and(AGGREGATE.AGGREGATE_ID.eq(old.aggregateId))
                    .and(AGGREGATE.SUPERSEDED_BY.isNull)
                    .and(AGGREGATE.TRANSMITTED_AT.isNull)
                    .and(AGGREGATE.HANDED_ON_AT.isNull)
                    .execute()
            check(marked == 1) { "the aggregate to supersede changed state during the intake" }
            recordSeen(compared, sourceRevision)
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
