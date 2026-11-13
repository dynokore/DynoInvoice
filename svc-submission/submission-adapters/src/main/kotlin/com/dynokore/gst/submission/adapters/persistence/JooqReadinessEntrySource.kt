package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ProvisionalRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.readiness.HoldingChecks
import com.dynokore.gst.submission.application.readiness.ReadinessEntrySource
import com.dynokore.gst.submission.application.readiness.ReleasedOverrideChecks
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.readiness.ReadinessEntry
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.Record

/**
 * [ReadinessEntrySource] over the register entries of an entity and period joined to their version rows (INV-REQ-570,
 * INV-AC-20479). One statement in the caller's open tenant transaction, with the tenant in the WHERE clause as well as
 * the row level security (CS-19). An entry without version rows is read as one state: NOT_SUBMITTABLE when out of
 * scope, HELD with its reason code when it has one, else QUEUED. An entry is overridden only when the check that held
 * it has a RELEASED override (compared by string equality); the document uuid is never compared. The result is null
 * (unavailable) when an entry has no transaction date: no date is guessed. A provisional entry of the entity and period
 * whose docUuid has no register row is read as one entry: INGESTED with no reason code, or HELD with its reason code,
 * on its stored transaction date (INV-AC-1778, INV-AC-1691); the anti-join keeps every docUuid distinct. Values are
 * never logged.
 */
class JooqReadinessEntrySource(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val overrides: ReleasedOverrideChecks,
    private val holding: HoldingChecks,
) : ReadinessEntrySource {
    private val entry = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
    private val version = Submission.SUBMISSION_
    private val provisional = ProvisionalRegisterEntry.PROVISIONAL_REGISTER_ENTRY

    override fun entries(
        entityRef: String,
        periodRef: String,
    ): List<ReadinessEntry>? {
        val records =
            dsl
                .select(
                    entry.DOC_UUID,
                    entry.IN_SCOPE,
                    entry.TRANSACTION_DATE,
                    entry.REASON_CODE,
                    version.STATE,
                ).from(entry)
                .leftJoin(version)
                .on(version.DOC_UUID.eq(entry.DOC_UUID))
                .and(version.TENANT_ID.eq(entry.TENANT_ID))
                .where(entry.TENANT_ID.eq(tenants.current().value))
                .and(entry.ENTITY_REF.eq(entityRef))
                .and(entry.PERIOD_REF.eq(periodRef))
                .orderBy(entry.DOC_UUID.asc(), version.VERSION.asc())
                .fetch()
        val groups = records.groupBy { checkNotNull(it.get(entry.DOC_UUID)) }
        if (groups.values.any { it.first().get(entry.TRANSACTION_DATE) == null }) return null
        val released by lazy { overrides.releasedCheckIds(entityRef, periodRef) }
        val registered = groups.map { (docUuid, group) -> toEntry(docUuid, group, entityRef, periodRef) { released } }
        return registered + provisionalEntries(entityRef, periodRef) { released }
    }

    private fun provisionalEntries(
        entityRef: String,
        periodRef: String,
        released: () -> Set<String>,
    ): List<ReadinessEntry> {
        val tenant = tenants.current().value
        val registered =
            dsl
                .selectOne()
                .from(entry)
                .where(entry.TENANT_ID.eq(provisional.TENANT_ID))
                .and(entry.DOC_UUID.eq(provisional.DOC_UUID))
        return dsl
            .select(provisional.DOC_UUID, provisional.TRANSACTION_DATE, provisional.REASON_CODE)
            .from(provisional)
            .where(provisional.TENANT_ID.eq(tenant))
            .and(provisional.ENTITY_REF.eq(entityRef))
            .and(provisional.PERIOD_REF.eq(periodRef))
            .andNotExists(registered)
            .orderBy(provisional.DOC_UUID.asc())
            .fetch()
            .map { row ->
                val docUuid = checkNotNull(row.get(provisional.DOC_UUID))
                val reason = row.get(provisional.REASON_CODE)
                val holder = holding.holdingCheckId(entityRef, periodRef, docUuid)
                ReadinessEntry(
                    docUuid = docUuid,
                    versions = listOf(if (reason == null) LifecycleState.INGESTED else LifecycleState.HELD),
                    transactionDate = checkNotNull(row.get(provisional.TRANSACTION_DATE)),
                    overridden = holder != null && holder in released(),
                    reasonCode = reason,
                )
            }
    }

    private fun toEntry(
        docUuid: String,
        group: List<Record>,
        entityRef: String,
        periodRef: String,
        released: () -> Set<String>,
    ): ReadinessEntry {
        val first = group.first()
        val reason = first.get(entry.REASON_CODE)
        val stored = group.mapNotNull { it.get(version.STATE) }.map(LifecycleState::valueOf)
        val states =
            when {
                stored.isNotEmpty() -> stored
                !checkNotNull(first.get(entry.IN_SCOPE)) -> listOf(LifecycleState.NOT_SUBMITTABLE)
                reason != null -> listOf(LifecycleState.HELD)
                else -> listOf(LifecycleState.QUEUED)
            }
        val holder = holding.holdingCheckId(entityRef, periodRef, docUuid)
        return ReadinessEntry(
            docUuid = docUuid,
            versions = states,
            transactionDate = checkNotNull(first.get(entry.TRANSACTION_DATE)),
            overridden = holder != null && holder in released(),
            reasonCode = reason,
        )
    }
}
