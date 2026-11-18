package com.dynokore.gst.submission.application.ordering

import com.dynokore.gst.submission.domain.ordering.CreditNoteOption
import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/**
 * The VP-59 election of an entity (INV-REQ-709): what happens to a credit note whose original cannot go first.
 * Fail closed: an unreadable, missing or unknown election throws, no default option is returned.
 */
fun interface CreditNoteOptions {
    fun at(
        tenant: TenantId,
        entityRef: String,
        on: LocalDate,
    ): CreditNoteOption
}
