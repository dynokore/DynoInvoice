package com.dynokore.gst.submission.adapters.policy

import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.submission.application.ordering.CreditNoteOptions
import com.dynokore.gst.submission.domain.ordering.CreditNoteOption
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/**
 * [CreditNoteOptions] over the configuration resolver (INV-REQ-709): the variation point VP-59 is resolved at entity
 * scope on the date passed in (no clock is read). Exactly one selected option is required and its key must equal
 * `RELEASE_ALONE` or `HOLD_FOR_APPROVER` as a whole string; anything else throws [IllegalStateException]. A resolver
 * exception propagates. Fail closed: no default option is held here.
 */
class PolicyCreditNoteOptions(
    private val reader: PolicyReader,
) : CreditNoteOptions {
    override fun at(
        tenant: TenantId,
        entityRef: String,
        on: LocalDate,
    ): CreditNoteOption {
        val selected =
            reader
                .resolve(
                    VariationPointKey(KEY),
                    PolicyScope.entity(tenant, entity = entityRef),
                    on,
                ).selected
        check(selected.size == 1) { "VP-59 must select exactly one option, found ${selected.size}" }
        return when (selected.single().key) {
            RELEASE_ALONE -> CreditNoteOption.RELEASE_ALONE
            HOLD_FOR_APPROVER -> CreditNoteOption.HOLD_FOR_APPROVER
            else -> error("VP-59 selected an unknown option")
        }
    }

    companion object {
        /** VP-59, read from the generated catalogue. */
        val KEY: String = VariationPoint.VP_59.id
        private const val RELEASE_ALONE = "RELEASE_ALONE"
        private const val HOLD_FOR_APPROVER = "HOLD_FOR_APPROVER"
    }
}
