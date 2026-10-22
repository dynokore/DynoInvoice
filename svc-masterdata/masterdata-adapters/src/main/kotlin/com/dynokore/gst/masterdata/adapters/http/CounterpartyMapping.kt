package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.masterdata.contract.model.ApprovedScheme
import com.dynokore.gst.masterdata.contract.model.Counterparty
import com.dynokore.gst.masterdata.contract.model.CounterpartyAddress
import com.dynokore.gst.masterdata.contract.model.CounterpartyResolvedIdentifiersInner
import com.dynokore.gst.masterdata.contract.model.CounterpartyResolvedIdentifiersInnerProvenance
import com.dynokore.gst.masterdata.contract.model.ResolutionFinding
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyVersion
import com.dynokore.gst.masterdata.domain.counterparty.Finding
import com.dynokore.gst.masterdata.domain.counterparty.Sameness
import com.dynokore.gst.masterdata.domain.counterparty.SchemeStatus
import com.dynokore.platform.partyregistry.Identifier
import com.dynokore.platform.partyregistry.ProvidedBy
import java.time.ZoneOffset

/** The contract forms of the counterparty master (TPL-08: mapping only, no rule). */
internal object CounterpartyMapping {
    /**
     * [schemes] are the items to show: those in force at a transaction date for a resolution, all of the version for
     * a write answer and for the history. [sameness] is set only by a resolution.
     */
    fun counterparty(
        version: CounterpartyVersion,
        schemes: List<SchemeStatus>,
        sameness: Sameness? = null,
    ): Counterparty {
        val content = version.content
        return Counterparty(
            partyRef = version.partyRef.value,
            resolvedIdentifiers = content.identifiers.map(::identifier),
            providedBy = Counterparty.ProvidedBy.forValue(content.providedBy.contractValue()),
            legalName = content.legalName,
            address =
                content.address?.let {
                    val line1 = it.line1
                    val postalCode = it.postalCode
                    if (line1 == null || postalCode == null) {
                        null
                    } else {
                        CounterpartyAddress(addressLine1 = line1, postalCode = postalCode, addressLine2 = it.line2)
                    }
                },
            sameGroup = sameness?.sameGroup,
            sameTenant = sameness?.sameTenant,
            arcsGstChargeAgreed = content.arcs?.agreed,
            approvedSchemes = schemes.map(::scheme).ifEmpty { null },
        )
    }

    fun finding(finding: Finding): ResolutionFinding {
        val row = ErrorCode.entries.single { it.code == finding.code.code }
        return ResolutionFinding(
            code = row.code,
            severity =
                ResolutionFinding.Severity.forValue(
                    checkNotNull(row.severity) {
                        "${row.code} has no severity in the catalogue"
                    }.name,
                ),
            disposition = checkNotNull(row.disposition) { "${row.code} has no disposition in the catalogue" }.name,
            field = finding.field,
        )
    }

    private fun identifier(identifier: Identifier) =
        CounterpartyResolvedIdentifiersInner(
            scheme = identifier.scheme.name,
            value = identifier.value,
            provenance =
                CounterpartyResolvedIdentifiersInnerProvenance(
                    providedBy =
                        CounterpartyResolvedIdentifiersInnerProvenance.ProvidedBy.forValue(
                            identifier.provenance.providedBy.contractValue(),
                        ),
                    evidenceRef = identifier.provenance.evidenceRef.value,
                    capturedAt = identifier.provenance.capturedAt.atOffset(ZoneOffset.UTC),
                ),
        )

    private fun scheme(status: SchemeStatus) =
        ApprovedScheme(
            scheme = status.scheme,
            effectiveFrom = status.effectiveFrom,
            effectiveTo = status.effectiveTo,
            evidenceRef = status.provenance.evidenceRef.value,
        )

    /** The contract enum lists COUNTERPARTY, INTERNAL and MIGRATED; no path of this task writes REGISTER. */
    private fun ProvidedBy.contractValue(): String {
        check(this != ProvidedBy.REGISTER) { "REGISTER provenance is not part of the published counterparty contract" }
        return name
    }
}
