package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.compliance.DocumentCoverage
import com.dynokore.gst.submission.application.compliance.ObligationRowSource
import com.dynokore.gst.submission.application.extension.EffectiveDueDate
import com.dynokore.gst.submission.application.extension.ExtensionInput
import com.dynokore.gst.submission.domain.metrics.ObligationRow
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.LocalDate
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.DueDateExtension as ExtensionTable

/**
 * [ObligationRowSource] over the register entries joined to their version rows (INV-REQ-1069, INV-Q-12800 A): one
 * read transaction, the due date mapped through [EffectiveDueDate] over the period's extension rows (INV-REQ-81),
 * tenant in the WHERE clause as well as the row level security (CS-19), no write, no cache.
 */
class JooqObligationRowSource(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : ObligationRowSource {
    override fun rows(
        tenant: TenantId,
        entityRef: String?,
        periodRef: String?,
    ): List<ObligationRow> {
        val entry = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
        val version = Submission.SUBMISSION_
        val extensions = HashMap<Pair<String, String>, List<ExtensionInput>>()
        val records =
            runner.inTransaction {
                extensions.putAll(readExtensions(dsl, tenant, entityRef, periodRef))
                dsl
                    .select(
                        entry.DOC_UUID,
                        entry.ENTITY_REF,
                        entry.PERIOD_REF,
                        entry.DUE_DATE,
                        entry.IN_SCOPE,
                        entry.KIND,
                        entry.TRANSACTION_DATE,
                        version.STATE,
                    ).from(entry)
                    .leftJoin(version)
                    .on(version.DOC_UUID.eq(entry.DOC_UUID))
                    .and(version.TENANT_ID.eq(entry.TENANT_ID))
                    .where(entry.TENANT_ID.eq(tenant.value))
                    .and(entityRef?.let { entry.ENTITY_REF.eq(it) } ?: DSL.noCondition())
                    .and(periodRef?.let { entry.PERIOD_REF.eq(it) } ?: DSL.noCondition())
                    .orderBy(entry.DOC_UUID.asc(), version.VERSION.asc())
                    .fetch()
            }
        return records.groupBy { checkNotNull(it.get(entry.DOC_UUID)) }.map { (docUuid, group) ->
            toRow(docUuid, group, extensions)
        }
    }

    private fun toRow(
        docUuid: String,
        group: List<Record>,
        extensions: Map<Pair<String, String>, List<ExtensionInput>>,
    ): ObligationRow {
        val entry = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
        val first = group.first()
        val states = group.mapNotNull { it.get(Submission.SUBMISSION_.STATE) }.map(LifecycleState::valueOf)
        val entityRef = checkNotNull(first.get(entry.ENTITY_REF))
        val periodRef = checkNotNull(first.get(entry.PERIOD_REF))
        val dueDate =
            effectiveDueDate(
                checkNotNull(first.get(entry.DUE_DATE)),
                docUuid,
                first.get(entry.TRANSACTION_DATE),
                extensions[entityRef to periodRef].orEmpty(),
            )
        return ObligationRow(
            docUuid = docUuid,
            entityRef = entityRef,
            periodRef = periodRef,
            dueDate = dueDate,
            inScope = checkNotNull(first.get(entry.IN_SCOPE)),
            hasSubmission = DocumentCoverage.hasSubmission(states),
            kind = RowKind.valueOf(checkNotNull(first.get(entry.KIND))),
            versionCount = maxOf(states.size, 1),
        )
    }
}

private val extensionLog = System.getLogger("com.dynokore.gst.submission.adapters.persistence.ExtensionRead")

/** The extension rows of the tenant per (entity, period) in order of recording; one select, no write. */
internal fun readExtensions(
    dsl: DSLContext,
    tenant: TenantId,
    entityRef: String?,
    periodRef: String?,
): Map<Pair<String, String>, List<ExtensionInput>> {
    val t = ExtensionTable.DUE_DATE_EXTENSION
    return dsl
        .selectFrom(t)
        .where(t.TENANT_ID.eq(tenant.value))
        .and(entityRef?.let { t.ENTITY_REF.eq(it) } ?: DSL.noCondition())
        .and(periodRef?.let { t.PERIOD_REF.eq(it) } ?: DSL.noCondition())
        .orderBy(t.RECORDED_AT.asc())
        .fetch()
        .groupBy({ checkNotNull(it.entityRef) to checkNotNull(it.periodRef) }) {
            ExtensionInput(
                checkNotNull(it.target),
                checkNotNull(it.scopeKind),
                it.scopeRef,
                checkNotNull(it.extendedDueDate),
            )
        }
}

/** The effective due date; a row whose scope cannot be ordered is counted in the log (no value), never matched. */
internal fun effectiveDueDate(
    registerDueDate: LocalDate,
    docUuid: String,
    transactionDate: LocalDate?,
    rows: List<ExtensionInput>,
): LocalDate {
    val result = EffectiveDueDate.resolve(registerDueDate, docUuid, transactionDate, rows)
    if (result.unorderable.isNotEmpty()) {
        extensionLog.log(
            System.Logger.Level.WARNING,
            "due date extension rows not orderable: {0}",
            result.unorderable.size,
        )
    }
    return result.dueDate
}
