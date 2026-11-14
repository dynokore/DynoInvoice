package com.dynokore.gst.ingestion.adapters.persistence

import com.dynokore.gst.ingestion.adapters.persistence.jooq.tables.FeedHealthState.Companion.FEED_HEALTH_STATE
import com.dynokore.gst.ingestion.application.feedhealth.FeedControl
import com.dynokore.gst.ingestion.application.feedhealth.FeedHealthKey
import com.dynokore.gst.ingestion.application.feedhealth.FeedHealthStore
import org.jooq.DSLContext
import com.dynokore.gst.ingestion.application.feedhealth.FeedHealthState as State

/**
 * jOOQ store of `ingestion.feed_health_state` (INV-REQ-815): the state of a control and the producer counter, one row
 * per (tenant, feed, entityRef, period, control). A save is one upsert in the caller's transaction (the transaction
 * runner binds the tenant; the table's row-level security checks it), so a rollback rolls the counter back. It is a
 * plain class: the composition root binds it.
 */
class FeedHealthJooqStore(
    private val dsl: DSLContext,
) : FeedHealthStore {
    override fun current(key: FeedHealthKey): State? =
        dsl
            .select(FEED_HEALTH_STATE.STATE)
            .from(FEED_HEALTH_STATE)
            .where(FEED_HEALTH_STATE.TENANT_ID.eq(key.tenantId))
            .and(FEED_HEALTH_STATE.FEED.eq(key.feed))
            .and(FEED_HEALTH_STATE.ENTITY_REF.eq(key.entityRef))
            .and(FEED_HEALTH_STATE.PERIOD.eq(key.period))
            .and(FEED_HEALTH_STATE.CONTROL.eq(key.control.name))
            .fetchOne(FEED_HEALTH_STATE.STATE)
            ?.let { State.valueOf(it) }

    override fun save(
        key: FeedHealthKey,
        state: State,
    ): Long =
        checkNotNull(
            dsl
                .insertInto(FEED_HEALTH_STATE)
                .set(FEED_HEALTH_STATE.TENANT_ID, key.tenantId)
                .set(FEED_HEALTH_STATE.FEED, key.feed)
                .set(FEED_HEALTH_STATE.ENTITY_REF, key.entityRef)
                .set(FEED_HEALTH_STATE.PERIOD, key.period)
                .set(FEED_HEALTH_STATE.CONTROL, key.control.name)
                .set(FEED_HEALTH_STATE.STATE, state.name)
                .set(FEED_HEALTH_STATE.STATE_VERSION, FIRST)
                .onConflict(
                    FEED_HEALTH_STATE.TENANT_ID,
                    FEED_HEALTH_STATE.FEED,
                    FEED_HEALTH_STATE.ENTITY_REF,
                    FEED_HEALTH_STATE.PERIOD,
                    FEED_HEALTH_STATE.CONTROL,
                ).doUpdate()
                .set(FEED_HEALTH_STATE.STATE, state.name)
                .set(FEED_HEALTH_STATE.STATE_VERSION, FEED_HEALTH_STATE.STATE_VERSION.plus(1L))
                .returning(FEED_HEALTH_STATE.STATE_VERSION)
                .fetchOne()
                ?.stateVersion,
        ) { "the upsert of the feed health state returned no row" }

    private companion object {
        const val FIRST = 1L
    }
}
