package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.application.register.RegisterDueDate
import com.dynokore.gst.submission.application.register.RegisterDueDateReader
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext

/**
 * [RegisterDueDateReader] over the register entry: one select of `doc_uuid` and `due_date` in a transaction of the
 * bound tenant; forced row level security applies (CS-19). It writes nothing.
 */
class JooqRegisterDueDateReader(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : RegisterDueDateReader {
    private val e = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY

    override fun forPeriod(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
    ): List<RegisterDueDate> =
        runner.inTransaction {
            dsl
                .select(e.DOC_UUID, e.DUE_DATE)
                .from(e)
                .where(e.TENANT_ID.eq(tenant.value))
                .and(e.ENTITY_REF.eq(entityRef))
                .and(e.PERIOD_REF.eq(periodRef))
                .fetch()
                .map { RegisterDueDate(checkNotNull(it[e.DOC_UUID]), checkNotNull(it[e.DUE_DATE])) }
        }
}
