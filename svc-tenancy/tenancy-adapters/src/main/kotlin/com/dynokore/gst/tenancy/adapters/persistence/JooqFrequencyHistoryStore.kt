package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.FILING_FREQUENCY_HISTORY
import com.dynokore.gst.tenancy.application.period.FrequencyEntry
import com.dynokore.gst.tenancy.application.period.FrequencyHistoryStore
import com.dynokore.gst.tenancy.application.period.HistoryState
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The filing-frequency history of the bound tenant's entities (jOOQ only, CS-18); forced RLS hides other tenants. */
class JooqFrequencyHistoryStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : FrequencyHistoryStore {
    override fun entries(entityId: UUID): List<FrequencyEntry> =
        dsl
            .selectFrom(FILING_FREQUENCY_HISTORY)
            .where(FILING_FREQUENCY_HISTORY.ENTITY_ID.eq(entityId))
            .orderBy(FILING_FREQUENCY_HISTORY.EFFECTIVE_FROM, FILING_FREQUENCY_HISTORY.RECORDED_AT)
            .fetch()
            .map {
                FrequencyEntry(
                    it.id.column("id"),
                    it.effectiveFrom.column("effective_from"),
                    it.frequency.column("frequency"),
                    it.irasApprovalReference.column("iras_approval_reference"),
                    HistoryState.valueOf(it.state.column("state")),
                )
            }

    override fun insert(
        entityId: UUID,
        entry: FrequencyEntry,
    ) {
        dsl
            .insertInto(FILING_FREQUENCY_HISTORY)
            .set(FILING_FREQUENCY_HISTORY.ID, entry.id)
            .set(FILING_FREQUENCY_HISTORY.TENANT_ID, tenants.current().value)
            .set(FILING_FREQUENCY_HISTORY.ENTITY_ID, entityId)
            .set(FILING_FREQUENCY_HISTORY.EFFECTIVE_FROM, entry.effectiveFrom)
            .set(FILING_FREQUENCY_HISTORY.FREQUENCY, entry.frequency)
            .set(FILING_FREQUENCY_HISTORY.IRAS_APPROVAL_REFERENCE, entry.irasApprovalReference)
            .set(FILING_FREQUENCY_HISTORY.STATE, entry.state.name)
            .set(FILING_FREQUENCY_HISTORY.RECORDED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .execute()
    }

    override fun setState(
        entityId: UUID,
        id: UUID,
        state: HistoryState,
    ) {
        val updated =
            dsl
                .update(FILING_FREQUENCY_HISTORY)
                .set(FILING_FREQUENCY_HISTORY.STATE, state.name)
                .where(FILING_FREQUENCY_HISTORY.ID.eq(id).and(FILING_FREQUENCY_HISTORY.ENTITY_ID.eq(entityId)))
                .execute()
        check(updated == 1) { "history entry $id of the entity was not found" }
    }
}
