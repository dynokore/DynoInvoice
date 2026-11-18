package com.dynokore.gst.submission.application.ordering

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId

/**
 * The version states of a credit note's preceding document (INV-REQ-709). Null: the register has no entry for the Doc
 * UUID. An empty list: an entry and no version row. Otherwise the states in ascending version order.
 */
fun interface PrecedingStates {
    fun of(
        tenant: TenantId,
        precedingDocUuid: String,
    ): List<LifecycleState>?
}
