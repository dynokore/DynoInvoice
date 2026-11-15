package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.application.drain.SafetyMarginAnswer
import com.dynokore.gst.submission.application.drain.SafetyMargins
import com.dynokore.gst.submission.application.filing.FilingDueDates
import com.dynokore.gst.submission.application.queue.AcknowledgementBasis
import com.dynokore.gst.submission.application.queue.AcknowledgementInputs
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext

/**
 * [AcknowledgementInputs] over `submission.obligation_register_entry`, [FilingDueDates] and [SafetyMargins]
 * (INV-T-22768): the entity and period of the document, the due date of that period and the margin in force on it.
 * No register entry or no due date gives a null due date and [SafetyMarginAnswer.Unavailable]. It reads no clock,
 * opens no transaction and writes nothing; an unreadable input throws and the caller decides (fail closed).
 */
class JooqAcknowledgementInputs(
    private val dsl: DSLContext,
    private val dueDates: FilingDueDates,
    private val margins: SafetyMargins,
) : AcknowledgementInputs {
    private val e = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY

    override fun of(
        tenant: TenantId,
        docUuid: String,
    ): AcknowledgementBasis {
        val entry =
            dsl
                .select(e.ENTITY_REF, e.PERIOD_REF)
                .from(e)
                .where(e.TENANT_ID.eq(tenant.value))
                .and(e.DOC_UUID.eq(docUuid))
                .fetchOne()
        val entityRef = entry?.get(e.ENTITY_REF)
        val periodRef = entry?.get(e.PERIOD_REF)
        val dueDate = if (entityRef == null || periodRef == null) null else dueDates.dueDateOf(entityRef, periodRef)
        return if (entityRef == null || dueDate == null) {
            AcknowledgementBasis(null, SafetyMarginAnswer.Unavailable)
        } else {
            AcknowledgementBasis(dueDate, margins.effective(tenant, entityRef, dueDate))
        }
    }
}
