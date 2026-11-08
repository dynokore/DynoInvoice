package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.application.register.RegisterListFactsStore
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext

/**
 * [RegisterListFactsStore] over the register entry (INV-REQ-975, INV-Q-16290 A): update only, never an insert, in one
 * transaction of the bound tenant, tenant in the WHERE clause as well as the row level security (CS-19). Writes the
 * eight fact columns and no other; values are never logged.
 */
class JooqRegisterListFactsStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : RegisterListFactsStore {
    private val entry = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY

    override fun store(
        tenant: TenantId,
        docUuid: String,
        facts: ListFacts,
    ): Boolean =
        runner.inTransaction {
            dsl
                .update(entry)
                .set(entry.DOCUMENT_NUMBER, facts.documentNumber)
                .set(entry.DOCUMENT_TYPE_CODE, facts.documentTypeCode)
                .set(entry.DIRECTION, facts.direction.name)
                .set(entry.COUNTERPARTY_NAME, facts.counterpartyName)
                .set(entry.COUNTERPARTY_UEN, facts.counterpartyUen)
                .set(entry.TAXABLE_AMOUNT, facts.taxableAmount)
                .set(entry.GST_AMOUNT, facts.gstAmount)
                .set(entry.CATEGORY_CODES, facts.categoryCodes.sorted().toTypedArray())
                .where(entry.TENANT_ID.eq(tenant.value))
                .and(entry.DOC_UUID.eq(docUuid))
                .execute() > 0
        }

    override fun facts(
        tenant: TenantId,
        docUuid: String,
    ): ListFacts? =
        runner
            .inTransaction {
                dsl
                    .selectFrom(entry)
                    .where(entry.TENANT_ID.eq(tenant.value))
                    .and(entry.DOC_UUID.eq(docUuid))
                    .fetchOne()
            }?.let { r ->
                if (r.documentNumber == null) {
                    null
                } else {
                    ListFacts(
                        documentNumber = checkNotNull(r.documentNumber),
                        documentTypeCode = checkNotNull(r.documentTypeCode),
                        direction = Direction.valueOf(checkNotNull(r.direction)),
                        counterpartyName = checkNotNull(r.counterpartyName),
                        counterpartyUen = checkNotNull(r.counterpartyUen),
                        taxableAmount = checkNotNull(r.taxableAmount),
                        gstAmount = checkNotNull(r.gstAmount),
                        categoryCodes = checkNotNull(r.categoryCodes).map { checkNotNull(it) }.toSet(),
                    )
                }
            }
}
