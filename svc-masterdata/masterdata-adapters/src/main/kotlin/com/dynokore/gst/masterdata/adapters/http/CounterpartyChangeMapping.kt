package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.contract.model.ApprovedScheme
import com.dynokore.gst.masterdata.contract.model.CounterpartyAddress
import com.dynokore.gst.masterdata.contract.model.ResolveCounterpartyRequestResolvedIdentifiersInner
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyChange
import com.dynokore.gst.masterdata.domain.counterparty.IdentifierInput
import com.dynokore.gst.masterdata.domain.counterparty.PostalAddress
import com.dynokore.gst.masterdata.domain.counterparty.SchemeInput
import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode

/** The contract write forms to the domain change (TPL-08: mapping only; the form checks are 400 SRT-001). */
internal object CounterpartyChangeMapping {
    @Suppress("LongParameterList") // the parts of the two write requests
    fun change(
        identifiers: List<ResolveCounterpartyRequestResolvedIdentifiersInner>?,
        legalName: String?,
        address: CounterpartyAddress?,
        arcsGstChargeAgreed: Boolean?,
        approvedSchemes: List<ApprovedScheme>?,
    ) = CounterpartyChange(
        identifiers =
            identifiers.orEmpty().map {
                val scheme =
                    IdentifierScheme.entries.firstOrNull { s -> s.name == it.scheme }
                        ?: invalid("scheme ${it.scheme} is not a scheme of this service")
                if (it.value.isBlank()) invalid("an identifier value must not be blank")
                IdentifierInput(scheme, it.value.trim())
            },
        legalName = legalName,
        address =
            address?.let {
                PostalAddress(it.addressLine1.blankToNull(), it.addressLine2.blankToNull(), it.postalCode.blankToNull())
            },
        arcsGstChargeAgreed = arcsGstChargeAgreed,
        approvedSchemes =
            approvedSchemes?.map {
                val end = it.effectiveTo
                if (end != null && end.isBefore(it.effectiveFrom)) invalid("effectiveTo is before effectiveFrom")
                SchemeInput(it.scheme, it.effectiveFrom, end, it.evidenceRef.blankToNull())
            },
    )

    fun evidence(evidenceRef: String): String {
        if (evidenceRef.isBlank()) invalid("evidenceRef must not be blank")
        return evidenceRef.trim()
    }

    fun invalid(message: String): Nothing = throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, message)

    private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
