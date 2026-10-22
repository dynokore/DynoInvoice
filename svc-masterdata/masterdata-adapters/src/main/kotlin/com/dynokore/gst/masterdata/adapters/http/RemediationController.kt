package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.application.counterparty.RecordCommand
import com.dynokore.gst.masterdata.application.counterparty.RecordCounterparty
import com.dynokore.gst.masterdata.contract.api.RemediationApi
import com.dynokore.gst.masterdata.contract.model.Counterparty
import com.dynokore.gst.masterdata.contract.model.ResolveCounterpartyRequest
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyChange
import com.dynokore.gst.masterdata.domain.counterparty.IdentifierInput
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.counterparty.PostalAddress
import com.dynokore.gst.masterdata.domain.counterparty.SchemeInput
import com.dynokore.gst.masterdata.domain.counterparty.WriteOrigin
import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * TPL-08. `resolveMasterDataGap` writes the counterparty identifiers, the legal name and address, the ARCS
 * written-agreement default and the approved scheme status, each with provenance: a caller is a business user, so every
 * value is `INTERNAL` with the evidence reference of the request and the capture time of the service. A body that
 * carries `providedBy`, `capturedAt` or `provenance` anywhere is refused before it gets here ([ProvenanceKeyAdvice]).
 * The other operation of the interface, `bulkResolveCounterparty`, belongs to INV-T-4944 and keeps the generated 501.
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin in the
// template)
open class RemediationController(
    private val record: RecordCounterparty,
) : RemediationApi {
    override fun resolveMasterDataGap(
        counterpartyRef: String,
        idempotencyKey: UUID,
        resolveCounterpartyRequest: ResolveCounterpartyRequest,
    ): ResponseEntity<Counterparty> {
        val request = resolveCounterpartyRequest
        val party = runCatching { PartyRef(counterpartyRef) }.getOrElse { invalid("counterpartyRef is invalid") }
        val change =
            CounterpartyChange(
                identifiers =
                    request.resolvedIdentifiers.map {
                        val scheme =
                            IdentifierScheme.entries.firstOrNull { s -> s.name == it.scheme }
                                ?: invalid("scheme ${it.scheme} is not a scheme of this service")
                        if (it.value.isBlank()) invalid("an identifier value must not be blank")
                        IdentifierInput(scheme, it.value.trim())
                    },
                legalName = request.legalName,
                address =
                    request.address?.let {
                        PostalAddress(
                            it.addressLine1.blankToNull(),
                            it.addressLine2.blankToNull(),
                            it.postalCode.blankToNull(),
                        )
                    },
                arcsGstChargeAgreed = request.arcsGstChargeAgreed,
                approvedSchemes =
                    request.approvedSchemes?.map {
                        val end = it.effectiveTo
                        if (end != null &&
                            end.isBefore(it.effectiveFrom)
                        ) {
                            invalid("effectiveTo is before effectiveFrom")
                        }
                        SchemeInput(it.scheme, it.effectiveFrom, end, it.evidenceRef.blankToNull())
                    },
            )
        if (request.evidenceRef.isBlank()) invalid("evidenceRef must not be blank")
        val version =
            record.handle(RecordCommand(party, change, request.evidenceRef.trim(), WriteOrigin.OPERATOR))
        return ResponseEntity.ok(CounterpartyMapping.counterparty(version, version.content.schemes))
    }

    private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun invalid(message: String): Nothing = throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, message)
}
