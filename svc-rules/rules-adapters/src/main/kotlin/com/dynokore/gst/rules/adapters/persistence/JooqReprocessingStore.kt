package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.REPROCESSING_RESUBMISSION
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.REPROCESSING_RUN
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.REPROCESSING_RUN_ENTITY
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.REPROCESSING_RUN_PAGE
import com.dynokore.gst.rules.application.ReprocessingRun
import com.dynokore.gst.rules.application.ReprocessingRunState
import com.dynokore.gst.rules.application.ReprocessingRunStore
import com.dynokore.gst.rules.application.ResubmissionStore
import com.dynokore.gst.rules.domain.reprocessing.ResubmissionEntry
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

/** The run a page event names: its key, found by the `reprocessingId` of the event under the tenant's row-level policy. */
data class ReprocessingRunKey(
    val packType: String,
    val version: String,
    val reprocessingId: String,
)

/**
 * The reprocessing run rows and the resubmission list over `rules.reprocessing_run`, `rules.reprocessing_run_entity`,
 * `rules.reprocessing_run_page` and `rules.reprocessing_resubmission` (INV-T-4978; INV-REQ-1003, INV-REQ-1004), jOOQ
 * only, in the caller's open transaction (the command's, the inbox consumer's or the worker's). Row-level security
 * hides every other tenant's rows. Nothing about a document is stored but the resubmission rows (INV-REQ-1196): a row
 * is inserted and never updated or deleted (the runtime login holds `SELECT, INSERT` on the table only).
 */
class JooqReprocessingStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : ReprocessingRunStore,
    ResubmissionStore {
    override fun find(
        packType: String,
        version: String,
    ): ReprocessingRun? =
        dsl
            .selectFrom(REPROCESSING_RUN)
            .where(REPROCESSING_RUN.TENANT_ID.eq(tenant()))
            .and(REPROCESSING_RUN.PACK_TYPE.eq(packType))
            .and(REPROCESSING_RUN.PACK_VERSION.eq(version))
            .fetchOne()
            ?.let(::runOf)

    override fun begin(
        packType: String,
        version: String,
        entities: List<String>,
    ): ReprocessingRun {
        dsl
            .insertInto(REPROCESSING_RUN)
            .set(REPROCESSING_RUN.TENANT_ID, tenant())
            .set(REPROCESSING_RUN.REPROCESSING_ID, UUID.randomUUID().toString())
            .set(REPROCESSING_RUN.PACK_TYPE, packType)
            .set(REPROCESSING_RUN.PACK_VERSION, version)
            .set(REPROCESSING_RUN.STATE, RUNNING)
            // a run with no entity has no page to wait for
            .set(REPROCESSING_RUN.LAST_PAGE_ARRIVED, entities.isEmpty())
            .execute()
        entities.distinct().forEach {
            dsl
                .insertInto(REPROCESSING_RUN_ENTITY)
                .set(REPROCESSING_RUN_ENTITY.TENANT_ID, tenant())
                .set(REPROCESSING_RUN_ENTITY.PACK_TYPE, packType)
                .set(REPROCESSING_RUN_ENTITY.PACK_VERSION, version)
                .set(REPROCESSING_RUN_ENTITY.ENTITY_REF, it)
                .execute()
        }
        return checkNotNull(find(packType, version)) { "the run just written is not readable" }
    }

    /** The `reprocessingId` of the run for the request event, or null when the run is not the tenant's. */
    fun reprocessingIdOf(
        packType: String,
        version: String,
    ): String? =
        dsl
            .select(REPROCESSING_RUN.REPROCESSING_ID)
            .from(REPROCESSING_RUN)
            .where(REPROCESSING_RUN.TENANT_ID.eq(tenant()))
            .and(REPROCESSING_RUN.PACK_TYPE.eq(packType))
            .and(REPROCESSING_RUN.PACK_VERSION.eq(version))
            .fetchOne(REPROCESSING_RUN.REPROCESSING_ID)

    /**
     * Admits the page [pageNumber] of [entityRef] of the run [reprocessingId]: the run key when the run is the
     * bound tenant's, the entity was requested and the page number is new, else null (a page of another run, another
     * tenant or a repeated number enqueues nothing).
     */
    fun admitPage(
        reprocessingId: String,
        entityRef: String,
        pageNumber: Int,
    ): ReprocessingRunKey? {
        val run =
            dsl
                .selectFrom(REPROCESSING_RUN)
                .where(REPROCESSING_RUN.TENANT_ID.eq(tenant()))
                .and(REPROCESSING_RUN.REPROCESSING_ID.eq(reprocessingId))
                .fetchOne() ?: return null
        val packType = checkNotNull(run.packType)
        val version = checkNotNull(run.packVersion)
        val requested =
            dsl.fetchExists(
                REPROCESSING_RUN_ENTITY,
                REPROCESSING_RUN_ENTITY.TENANT_ID.eq(tenant()),
                REPROCESSING_RUN_ENTITY.PACK_TYPE.eq(packType),
                REPROCESSING_RUN_ENTITY.PACK_VERSION.eq(version),
                REPROCESSING_RUN_ENTITY.ENTITY_REF.eq(entityRef),
            )
        if (!requested) return null
        val stored =
            dsl
                .insertInto(REPROCESSING_RUN_PAGE)
                .set(REPROCESSING_RUN_PAGE.TENANT_ID, tenant())
                .set(REPROCESSING_RUN_PAGE.PACK_TYPE, packType)
                .set(REPROCESSING_RUN_PAGE.PACK_VERSION, version)
                .set(REPROCESSING_RUN_PAGE.ENTITY_REF, entityRef)
                .set(REPROCESSING_RUN_PAGE.PAGE_NUMBER, pageNumber)
                .onConflictDoNothing()
                .execute()
        return if (stored == 0) null else ReprocessingRunKey(packType, version, reprocessingId)
    }

    override fun pageArrived(
        packType: String,
        version: String,
        entityRef: String,
        last: Boolean,
        docUuids: List<String>,
    ): ReprocessingRun {
        if (last) {
            dsl
                .update(REPROCESSING_RUN_ENTITY)
                .set(REPROCESSING_RUN_ENTITY.LAST_PAGE_ARRIVED, true)
                .where(REPROCESSING_RUN_ENTITY.TENANT_ID.eq(tenant()))
                .and(REPROCESSING_RUN_ENTITY.PACK_TYPE.eq(packType))
                .and(REPROCESSING_RUN_ENTITY.PACK_VERSION.eq(version))
                .and(REPROCESSING_RUN_ENTITY.ENTITY_REF.eq(entityRef))
                .execute()
        }
        val allLast =
            !dsl.fetchExists(
                REPROCESSING_RUN_ENTITY,
                REPROCESSING_RUN_ENTITY.TENANT_ID.eq(tenant()),
                REPROCESSING_RUN_ENTITY.PACK_TYPE.eq(packType),
                REPROCESSING_RUN_ENTITY.PACK_VERSION.eq(version),
                REPROCESSING_RUN_ENTITY.LAST_PAGE_ARRIVED.isFalse,
            )
        dsl
            .update(REPROCESSING_RUN)
            .set(REPROCESSING_RUN.RECEIVED, REPROCESSING_RUN.RECEIVED.plus(docUuids.distinct().size))
            .set(REPROCESSING_RUN.LAST_PAGE_ARRIVED, allLast)
            .where(runKey(packType, version))
            .execute()
        return checkNotNull(find(packType, version)) { "no run for $packType $version" }
    }

    override fun processed(
        packType: String,
        version: String,
        docUuid: String,
    ): ReprocessingRun {
        dsl
            .update(REPROCESSING_RUN)
            .set(REPROCESSING_RUN.PROCESSED, REPROCESSING_RUN.PROCESSED.plus(1))
            .where(runKey(packType, version))
            .execute()
        return checkNotNull(find(packType, version)) { "no run for $packType $version" }
    }

    override fun complete(
        packType: String,
        version: String,
    ): ReprocessingRun {
        dsl
            .update(REPROCESSING_RUN)
            .set(REPROCESSING_RUN.STATE, COMPLETE)
            .where(runKey(packType, version))
            .execute()
        return checkNotNull(find(packType, version)) { "no run for $packType $version" }
    }

    /** Idempotent by (pack type, version, Doc UUID): the first row stays, a repeat inserts nothing. */
    override fun add(
        packType: String,
        version: String,
        entry: ResubmissionEntry,
    ) {
        dsl
            .insertInto(REPROCESSING_RESUBMISSION)
            .set(REPROCESSING_RESUBMISSION.TENANT_ID, tenant())
            .set(REPROCESSING_RESUBMISSION.PACK_TYPE, packType)
            .set(REPROCESSING_RESUBMISSION.PACK_VERSION, version)
            .set(REPROCESSING_RESUBMISSION.DOC_UUID, UUID.fromString(entry.docUuid))
            .set(REPROCESSING_RESUBMISSION.SUPERSEDED_VERSION, entry.supersededVersion.toInt())
            .onConflictDoNothing()
            .execute()
    }

    /** The resubmission list of a run: Doc UUID and the version superseded, by Doc UUID. */
    fun resubmissions(
        packType: String,
        version: String,
    ): List<ResubmissionEntry> =
        dsl
            .select(REPROCESSING_RESUBMISSION.DOC_UUID, REPROCESSING_RESUBMISSION.SUPERSEDED_VERSION)
            .from(REPROCESSING_RESUBMISSION)
            .where(REPROCESSING_RESUBMISSION.TENANT_ID.eq(tenant()))
            .and(REPROCESSING_RESUBMISSION.PACK_TYPE.eq(packType))
            .and(REPROCESSING_RESUBMISSION.PACK_VERSION.eq(version))
            .orderBy(REPROCESSING_RESUBMISSION.DOC_UUID)
            .fetch()
            .map {
                ResubmissionEntry(
                    checkNotNull(it.get(REPROCESSING_RESUBMISSION.DOC_UUID)).toString(),
                    checkNotNull(it.get(REPROCESSING_RESUBMISSION.SUPERSEDED_VERSION)).toString(),
                )
            }

    private fun runKey(
        packType: String,
        version: String,
    ) = DSL.and(
        REPROCESSING_RUN.TENANT_ID.eq(tenant()),
        REPROCESSING_RUN.PACK_TYPE.eq(packType),
        REPROCESSING_RUN.PACK_VERSION.eq(version),
    )

    private fun tenant(): UUID = tenants.current().value

    private fun runOf(r: org.jooq.Record): ReprocessingRun =
        ReprocessingRun(
            packType = checkNotNull(r.get(REPROCESSING_RUN.PACK_TYPE)),
            version = checkNotNull(r.get(REPROCESSING_RUN.PACK_VERSION)),
            state = ReprocessingRunState.valueOf(checkNotNull(r.get(REPROCESSING_RUN.STATE))),
            lastPageArrived = checkNotNull(r.get(REPROCESSING_RUN.LAST_PAGE_ARRIVED)),
            received = checkNotNull(r.get(REPROCESSING_RUN.RECEIVED)),
            processed = checkNotNull(r.get(REPROCESSING_RUN.PROCESSED)),
        )

    private companion object {
        const val RUNNING = "RUNNING"
        const val COMPLETE = "COMPLETE"
    }
}
