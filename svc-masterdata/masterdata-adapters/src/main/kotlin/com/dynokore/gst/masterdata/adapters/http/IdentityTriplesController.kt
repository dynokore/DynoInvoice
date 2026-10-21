package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.application.identity.ConfirmCommand
import com.dynokore.gst.masterdata.application.identity.ConfirmIdentityTripleChange
import com.dynokore.gst.masterdata.application.identity.ConfirmOutcome
import com.dynokore.gst.masterdata.application.identity.ProposeCommand
import com.dynokore.gst.masterdata.application.identity.ProposeIdentityTripleChange
import com.dynokore.gst.masterdata.contract.api.IdentityTriplesApi
import com.dynokore.gst.masterdata.contract.model.ConfigureIdentityTripleRequest
import com.dynokore.gst.masterdata.contract.model.IdentityTriple
import com.dynokore.gst.masterdata.contract.model.PendingIdentityTripleChange
import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.PendingChange
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.gst.masterdata.domain.identity.TripleVersion
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * TPL-08. `configureIdentityTriple` proposes and `confirmIdentityTripleChange` confirms (rulings INV-Q-7884 A and
 * INV-Q-7888 A). The confirmer is the authenticated subject of the confirming call, never a body field.
 *
 * `configureIdentityTriple` answers 501 for a valid proposal: the published request carries no constitution and the
 * owner question INV-Q-7878 is open, so no constitution is assumed (see the merge request, "Gaps"). [propose] holds the
 * whole path, from a constitution to the 202 body, and waits only for that answer.
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin in the
// template)
open class IdentityTriplesController(
    private val proposeChange: ProposeIdentityTripleChange,
    private val confirmChange: ConfirmIdentityTripleChange,
    private val tenants: TenantContext,
) : IdentityTriplesApi {
    @Suppress("DEPRECATION") // the property is deprecated and refused: reading it is how the refusal is made
    override fun configureIdentityTriple(
        entityRef: String,
        idempotencyKey: UUID,
        configureIdentityTripleRequest: ConfigureIdentityTripleRequest,
    ): ResponseEntity<IdentityTriple> {
        // A body that carries secondConfirmation is refused and nothing is recorded (a separate call confirms).
        if (configureIdentityTripleRequest.secondConfirmation != null) {
            invalid("secondConfirmation is not accepted: the confirmation is a separate call")
        }
        return super.configureIdentityTriple(entityRef, idempotencyKey, configureIdentityTripleRequest)
    }

    /** The proposal for [constitution]: 202 with the pending change; nothing is put in force. */
    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    open fun propose(
        entityRef: String,
        constitution: String,
        request: ConfigureIdentityTripleRequest,
    ): ResponseEntity<IdentityTriple> {
        if (request.secondConfirmation != null) invalid("secondConfirmation is not accepted")
        val proposer = PersonRef(checkNotNull(tenants.currentActor()) { "no actor is bound" })
        val change =
            proposeChange.handle(
                ProposeCommand(
                    entity = runCatching { EntityRef(entityRef) }.getOrElse { invalid("entityRef is invalid") },
                    constitution =
                        runCatching { Constitution(constitution) }.getOrElse { invalid("constitution is invalid") },
                    triple =
                        runCatching {
                            com.dynokore.gst.masterdata.domain.identity
                                .IdentityTriple(request.senderId, request.supplierId, request.supplierGstn)
                        }.getOrElse { invalid("the identity triple is invalid") },
                    effectiveFrom = request.effectiveFrom,
                    proposer = proposer,
                ),
            )
        // The generated interface types every response as the 200 schema; the 202 body is the pending change. Jackson
        // writes the runtime type, so the cast only crosses the generator's single return type.
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(pendingOf(change)) as ResponseEntity<IdentityTriple>
    }

    override fun confirmIdentityTripleChange(
        entityRef: String,
        changeRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<IdentityTriple> {
        val entity = runCatching { EntityRef(entityRef) }.getOrElse { throw ApiRefusal(ClosedErrorCode.NOT_FOUND) }
        val confirmer = PersonRef(checkNotNull(tenants.currentActor()) { "no actor is bound" })
        return when (val outcome = confirmChange.handle(ConfirmCommand(entity, changeRef, confirmer))) {
            is ConfirmOutcome.Confirmed -> ResponseEntity.ok(tripleOf(outcome.version))
            else -> throw refusalOf(outcome)
        }
    }

    private fun refusalOf(outcome: ConfirmOutcome): ApiRefusal =
        when (outcome) {
            ConfirmOutcome.UnknownChange -> {
                ApiRefusal(ClosedErrorCode.NOT_FOUND)
            }

            is ConfirmOutcome.Refused -> {
                ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
            }

            ConfirmOutcome.NotPending -> {
                ApiRefusal(ClosedErrorCode.STALE_AGGREGATE, "the change is no longer PENDING_CONFIRMATION")
            }

            is ConfirmOutcome.Confirmed -> {
                error("a confirmed change is not a refusal")
            }
        }

    private fun pendingOf(change: PendingChange) =
        PendingIdentityTripleChange(
            changeRef = change.changeRef,
            entityRef = change.version.entity.value,
            senderId = change.version.triple.senderId,
            supplierId = change.version.triple.supplierId,
            supplierGstn = change.version.triple.supplierGstn,
            effectiveFrom = change.version.effectiveFrom,
            state = PendingIdentityTripleChange.State.PENDING_CONFIRMATION,
            proposedBy = change.proposedBy.value,
        )

    private fun tripleOf(version: TripleVersion) =
        IdentityTriple(
            senderId = version.triple.senderId,
            supplierId = version.triple.supplierId,
            supplierGstn = version.triple.supplierGstn,
            entityRef = version.entity.value,
            effectiveFrom = version.effectiveFrom,
        )

    private fun invalid(message: String): Nothing = throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, message)
}
