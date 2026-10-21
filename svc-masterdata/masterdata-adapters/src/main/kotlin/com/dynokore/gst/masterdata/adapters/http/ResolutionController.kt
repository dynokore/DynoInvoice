package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.application.identity.ResolveCommand
import com.dynokore.gst.masterdata.application.identity.ResolveIdentityTriple
import com.dynokore.gst.masterdata.contract.api.ResolutionApi
import com.dynokore.gst.masterdata.contract.model.IdentityTripleFacts
import com.dynokore.gst.masterdata.contract.model.ResolutionResult
import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.Resolution
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.MasterdataErrorCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * TPL-08. `resolveCounterparty`, the identity-triple half (INV-REQ-310): the triple in force for (entity, constitution)
 * on the transaction date, or 422 MDM-005 when none is configured (ruling INV-Q-7886 A). The counterparty half
 * (partyScheme, partyValue) belongs to INV-T-4943 and is not answered here.
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin in the
// template)
open class ResolutionController(
    private val resolve: ResolveIdentityTriple,
) : ResolutionApi {
    override fun resolveCounterparty(
        entityRef: String,
        constitution: String,
        transactionDate: LocalDate,
        partyScheme: String?,
        partyValue: String?,
    ): ResponseEntity<ResolutionResult> {
        val entity = runCatching { EntityRef(entityRef) }.getOrElse { invalid("entityRef is invalid") }
        val legal = runCatching { Constitution(constitution) }.getOrElse { invalid("constitution is invalid") }
        return when (val resolution = resolve.handle(ResolveCommand(entity, legal, transactionDate))) {
            is Resolution.Resolved -> {
                val triple = resolution.version.triple
                ResponseEntity.ok(
                    ResolutionResult(IdentityTripleFacts(triple.senderId, triple.supplierId, triple.supplierGstn)),
                )
            }

            is Resolution.Blocked -> {
                throw MasterdataRefusal(
                    MasterdataErrorCode.IDENTITY_TRIPLE_NOT_CONFIGURED,
                    "No identity triple is configured for constitution ${legal.code} of entity ${entity.value}",
                )
            }
        }
    }

    private fun invalid(message: String): Nothing = throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, message)
}
