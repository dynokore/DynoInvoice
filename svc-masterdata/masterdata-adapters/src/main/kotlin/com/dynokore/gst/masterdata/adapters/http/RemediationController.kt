package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.application.counterparty.RecordCommand
import com.dynokore.gst.masterdata.application.counterparty.RecordCounterparty
import com.dynokore.gst.masterdata.contract.api.RemediationApi
import com.dynokore.gst.masterdata.contract.model.Counterparty
import com.dynokore.gst.masterdata.contract.model.ResolveCounterpartyRequest
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.counterparty.WriteOrigin
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
        // optional in the schema only (a required property would break clients); the service refuses its absence
        // (INV-Q-8480 A). A blank value names no entity.
        val entity = request.entityRef?.trim()?.takeIf { it.isNotEmpty() } ?: invalid("entityRef is required")
        val change =
            CounterpartyChangeMapping.change(
                request.resolvedIdentifiers,
                request.legalName,
                request.address,
                request.arcsGstChargeAgreed,
                request.approvedSchemes,
            )
        val evidence = CounterpartyChangeMapping.evidence(request.evidenceRef)
        val version = record.handle(RecordCommand(entity, party, change, evidence, WriteOrigin.OPERATOR))
        return ResponseEntity.ok(CounterpartyMapping.counterparty(version, version.content.schemes))
    }

    private fun invalid(message: String): Nothing = CounterpartyChangeMapping.invalid(message)
}
