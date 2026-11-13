package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.extension.ExtensionInput
import com.dynokore.gst.submission.application.listing.SourceRead
import com.dynokore.gst.submission.application.listing.SubmissionRowSource
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.gst.submission.domain.listing.SubmissionRow
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.Record

/**
 * [SubmissionRowSource] over the version rows joined to the register entry of the same document (INV-REQ-975,
 * INV-Q-16290 A): one select, plus one select of the period's extension rows mapped through EffectiveDueDate
 * (INV-REQ-81), in one read transaction of the bound tenant, tenant in the WHERE clause as well as the row level
 * security (CS-19). No condition on state, amount, counterparty or category: the filter is the domain's.
 * A version row that cannot form a row (entry without facts, no submission type, no transaction date, unpaired
 * acknowledgement) is counted in `withoutFacts`, never mapped with an invented value. Values are never logged.
 */
class JooqSubmissionRowSource(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : SubmissionRowSource {
    private val entry = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
    private val version = Submission.SUBMISSION_

    override fun rows(
        tenant: TenantId,
        entityRef: String,
        periodRef: String,
    ): SourceRead {
        var extensions: List<ExtensionInput> = emptyList()
        val records =
            runner.inTransaction {
                extensions = readExtensions(dsl, tenant, entityRef, periodRef)[entityRef to periodRef].orEmpty()
                dsl
                    .select(
                        version.DOC_UUID,
                        version.VERSION,
                        version.STATE,
                        version.SUBMISSION_TYPE,
                        version.TRANSACTION_DATE,
                        version.SUBMITTED_AT,
                        version.ACKNOWLEDGED_AT,
                        version.ACKNOWLEDGEMENT_ID,
                        entry.DUE_DATE,
                        entry.DOCUMENT_NUMBER,
                        entry.DOCUMENT_TYPE_CODE,
                        entry.DIRECTION,
                        entry.COUNTERPARTY_NAME,
                        entry.COUNTERPARTY_UEN,
                        entry.TAXABLE_AMOUNT,
                        entry.GST_AMOUNT,
                        entry.CATEGORY_CODES,
                    ).from(version)
                    .join(entry)
                    .on(entry.DOC_UUID.eq(version.DOC_UUID))
                    .and(entry.TENANT_ID.eq(version.TENANT_ID))
                    .where(version.TENANT_ID.eq(tenant.value))
                    .and(entry.ENTITY_REF.eq(entityRef))
                    .and(entry.PERIOD_REF.eq(periodRef))
                    .fetch()
            }
        val rows = records.mapNotNull { toRow(it, extensions) }
        return SourceRead(rows, records.size - rows.size)
    }

    private fun toRow(
        r: Record,
        extensions: List<ExtensionInput>,
    ): SubmissionRow? {
        val type = r.get(version.SUBMISSION_TYPE)
        val txDate = r.get(version.TRANSACTION_DATE)
        val ackAt = r.get(version.ACKNOWLEDGED_AT)
        val ackId = r.get(version.ACKNOWLEDGEMENT_ID)
        val number = r.get(entry.DOCUMENT_NUMBER)
        val unpairedAcknowledgement = (ackAt == null) != (ackId == null)
        val incomplete = type == null || txDate == null || number == null
        return if (incomplete || unpairedAcknowledgement) {
            null
        } else {
            SubmissionRow(
                docUuid = checkNotNull(r.get(version.DOC_UUID)),
                version = checkNotNull(r.get(version.VERSION)),
                submissionType = SubmissionKind.valueOf(checkNotNull(type)),
                state = LifecycleState.valueOf(checkNotNull(r.get(version.STATE))),
                dueDate =
                    effectiveDueDate(
                        checkNotNull(r.get(entry.DUE_DATE)),
                        checkNotNull(r.get(version.DOC_UUID)),
                        txDate,
                        extensions,
                    ),
                documentNumber = checkNotNull(number),
                documentTypeCode = checkNotNull(r.get(entry.DOCUMENT_TYPE_CODE)),
                direction = Direction.valueOf(checkNotNull(r.get(entry.DIRECTION))),
                counterpartyName = checkNotNull(r.get(entry.COUNTERPARTY_NAME)),
                counterpartyUen = checkNotNull(r.get(entry.COUNTERPARTY_UEN)),
                transactionDate = checkNotNull(txDate),
                taxableAmount = checkNotNull(r.get(entry.TAXABLE_AMOUNT)),
                gstAmount = checkNotNull(r.get(entry.GST_AMOUNT)),
                categoryCodes = checkNotNull(r.get(entry.CATEGORY_CODES)).map { checkNotNull(it) }.toSet(),
                submittedAt = r.get(version.SUBMITTED_AT)?.toInstant(),
                acknowledgedAt = ackAt?.toInstant(),
                acknowledgementId = ackId,
            )
        }
    }
}
