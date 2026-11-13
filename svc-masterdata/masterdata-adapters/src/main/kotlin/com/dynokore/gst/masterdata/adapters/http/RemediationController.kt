package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.application.counterparty.RecordCommand
import com.dynokore.gst.masterdata.application.counterparty.RecordCounterparty
import com.dynokore.gst.masterdata.application.permission.MasterdataPermissions
import com.dynokore.gst.masterdata.application.permission.TenantPermission
import com.dynokore.gst.masterdata.contract.api.RemediationApi
import com.dynokore.gst.masterdata.contract.model.BulkResolveRequest
import com.dynokore.gst.masterdata.contract.model.BulkResolveResult
import com.dynokore.gst.masterdata.contract.model.Counterparty
import com.dynokore.gst.masterdata.contract.model.ResolveCounterpartyRequest
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.counterparty.WriteOrigin
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * TPL-08. `resolveMasterDataGap` writes the counterparty identifiers, the legal name and address, the ARCS
 * written-agreement default and the approved scheme status, each with provenance: a caller is a business user, so every
 * value is `INTERNAL` with the evidence reference of the request and the capture time of the service. A body that
 * carries `providedBy`, `capturedAt` or `provenance` anywhere is refused before it gets here ([ProvenanceKeyAdvice]).
 *
 * `bulkResolveCounterparty` (INV-T-4944, INV-REQ-317 b5, INV-REQ-941 b8) writes the resolved identifiers to the one
 * counterparty its `documents` name, once, and reports the number of distinct documents resolved. The permission is
 * the first statement (INV-Q-10027, INV-Q-17215 A); a request without `documents`, whose `docUuid` set differs from the
 * `docUuids` set or whose items name more than one distinct `counterpartyRef` is refused 400 SRT-001 before anything is
 * written (one UEN is never written across counterparties). More than 5,000 items are refused 413 SRT-014 before the
 * handler ([ApiRefusalAdvice]).
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin in the
// template)
open class RemediationController(
    private val record: RecordCounterparty,
    private val permission: TenantPermission,
    private val tenants: TenantContext,
) : RemediationApi {
    override fun bulkResolveCounterparty(
        idempotencyKey: UUID,
        bulkResolveRequest: BulkResolveRequest,
    ): ResponseEntity<BulkResolveResult> {
        val request = bulkResolveRequest
        val actor = tenants.currentActor().orEmpty()
        if (!permission.holds(actor, MasterdataPermissions.MASTERDATA_EXCEPTION_RESOLVE)) {
            throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        }
        val entity = request.entityRef?.trim()?.takeIf { it.isNotEmpty() } ?: invalid("entityRef is required")
        val documents = request.documents ?: invalid("documents is required")
        val docs = request.docUuids.toSet()
        if (documents.map { it.docUuid }.toSet() != docs) invalid("the documents do not match docUuids")
        val parties = documents.map { it.counterpartyRef }.toSet()
        if (parties.size != 1) invalid("the documents must name exactly one counterparty")
        val party = runCatching { PartyRef(parties.single()) }.getOrElse { invalid("counterpartyRef is invalid") }
        val change = CounterpartyChangeMapping.change(request.resolvedIdentifiers, null, null, null, null)
        val evidence = CounterpartyChangeMapping.evidence(request.evidenceRef)
        record.handle(
            RecordCommand(entity, party, change, evidence, WriteOrigin.OPERATOR, request.docUuids.distinct()),
        )
        return ResponseEntity.ok(BulkResolveResult(resolvedCount = docs.size, unchangedCount = 0))
    }

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
