package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT
import com.dynokore.gst.canonical.application.document.DocumentHoldStore
import com.dynokore.gst.canonical.application.document.DocumentStates
import com.dynokore.gst.canonical.application.document.HoldableDocument
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.util.UUID

/**
 * TPL-11. jOOQ only. The DSLContext is the one of the open `canonical` transaction; the tenant condition is explicit
 * and forced row-level security stays behind it. The entity text, the document date and the period text come from the
 * columns the record pipeline persists, never from another column or a default (INV-REQ-479).
 */
class JooqDocumentHoldStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : DocumentHoldStore {
    override fun lockForHold(docUuid: UUID): HoldableDocument? =
        dsl
            .select(
                DOCUMENT.ENTITY_REF,
                DOCUMENT.DIRECTION,
                DOCUMENT.DOCUMENT_DATE,
                DOCUMENT.PRESCRIBED_PERIOD_REF,
                DOCUMENT.STATE,
                DOCUMENT.HOLD_REASONS,
                DOCUMENT.POSTING_TIMESTAMP,
                DOCUMENT.INVOICE_ISSUED_OR_RECEIVED,
            ).from(DOCUMENT)
            .where(DOCUMENT.TENANT_ID.eq(tenants.current().value))
            .and(DOCUMENT.DOC_UUID.eq(docUuid))
            .forUpdate()
            .fetchOne()
            ?.let { row ->
                HoldableDocument(
                    docUuid = docUuid,
                    entityRef = requireStored(docUuid, "entity_ref", row.get(DOCUMENT.ENTITY_REF)),
                    direction = Direction.valueOf(checkNotNull(row.get(DOCUMENT.DIRECTION))),
                    documentDate = requireStored(docUuid, "document_date", row.get(DOCUMENT.DOCUMENT_DATE)),
                    prescribedPeriodRef =
                        requireStored(docUuid, "prescribed_period_ref", row.get(DOCUMENT.PRESCRIBED_PERIOD_REF)),
                    state = checkNotNull(row.get(DOCUMENT.STATE)),
                    holdReasons = row.get(DOCUMENT.HOLD_REASONS)?.filterNotNull().orEmpty(),
                    postingTimestamp = row.get(DOCUMENT.POSTING_TIMESTAMP),
                    invoiceIssuedOrReceived = row.get(DOCUMENT.INVOICE_ISSUED_OR_RECEIVED),
                )
            }

    override fun markHeld(
        docUuid: UUID,
        reasons: List<String>,
    ) {
        dsl
            .update(DOCUMENT)
            .set(DOCUMENT.STATE, DocumentStates.HELD)
            .set(DOCUMENT.HOLD_REASONS, reasons.toTypedArray())
            .where(DOCUMENT.TENANT_ID.eq(tenants.current().value))
            .and(DOCUMENT.DOC_UUID.eq(docUuid))
            .execute()
    }

    private fun <T : Any> requireStored(
        docUuid: UUID,
        column: String,
        value: T?,
    ): T = checkNotNull(value) { "document $docUuid has no $column stored" }
}
