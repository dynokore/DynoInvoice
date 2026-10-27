package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.breakglass.BreakGlassSession
import com.dynokore.gst.access.application.breakglass.RecordBreakGlassActionUseCase
import com.dynokore.gst.access.application.breakglass.RecordBreakGlassOutcome
import com.dynokore.gst.access.application.breakglass.UnlockBreakGlassOutcome
import com.dynokore.gst.access.application.breakglass.UnlockBreakGlassUseCase
import com.dynokore.gst.access.contract.api.BreakGlassApi
import com.dynokore.gst.access.contract.model.BreakGlassActionRequest
import com.dynokore.gst.access.contract.model.BreakGlassActionResponse
import com.dynokore.gst.access.contract.model.BreakGlassResponse
import com.dynokore.gst.access.contract.model.UnlockBreakGlassRequest
import com.dynokore.gst.access.domain.breakglass.BreakGlassAction
import com.dynokore.gst.access.domain.breakglass.BreakGlassRules
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.ServiceCaller
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import java.util.UUID

/**
 * TPL-08. unlockBreakGlass and recordBreakGlassAction (INV-REQ-896, INV-REQ-897, INV-REQ-1269): the unlock is for the
 * bound tenant only; fewer than two different people is refused (400, SRT-001) and a pair that does not hold the
 * roles is refused (403). The session length is never a request field: the 201 carries the VP-08 value, the expiry and
 * the four permitted actions. Recording on a session that is resealed or past its expiry is 409. The contract lists no
 * error code for 409, so the closed code of a conflict (SRT-008) carries a detail that says what is wrong.
 * signInBreakGlass and resealBreakGlass are not served by this controller yet (the sealing credential is open).
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin in the
// template)
open class BreakGlassController(
    private val unlock: UnlockBreakGlassUseCase,
    private val record: RecordBreakGlassActionUseCase,
    private val tenants: TenantContext,
    private val serviceCaller: ServiceCaller,
) : BreakGlassApi {
    override fun unlockBreakGlass(
        idempotencyKey: UUID,
        unlockBreakGlassRequest: UnlockBreakGlassRequest,
    ): ResponseEntity<BreakGlassResponse> {
        val tenant = tenants.current().toString()
        // an unlock is for the caller's own tenant only
        if (unlockBreakGlassRequest.tenantRef != tenant) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        val approvers = unlockBreakGlassRequest.approvedBy.map { GrantRequests.identityOf(it) }
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound" }
        return when (val outcome = unlock.handle(actor, TenantRef(tenant), approvers)) {
            is UnlockBreakGlassOutcome.Unlocked -> {
                ResponseEntity.status(HttpStatus.CREATED).body(unlocked(outcome.session))
            }

            UnlockBreakGlassOutcome.PairOfOne -> {
                GrantRequests.invalid("break-glass needs two different people: a pair of one person is refused")
            }

            UnlockBreakGlassOutcome.PairNotHeld -> {
                throw ApiRefusal(
                    ClosedErrorCode.ACCESS_DENIED,
                    "the pair does not hold the roles of the break-glass setting",
                )
            }
        }
    }

    override fun recordBreakGlassAction(
        unlockRef: String,
        idempotencyKey: UUID,
        breakGlassActionRequest: BreakGlassActionRequest,
    ): ResponseEntity<BreakGlassActionResponse> {
        // an unmapped caller is refused before the use case runs (INV-Q-9113 A)
        val callerService = serviceCaller.current() ?: throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound" }
        val action = BreakGlassAction.valueOf(breakGlassActionRequest.action.name)
        return when (val outcome = record.handle(actor, unlockRef, action, breakGlassActionRequest.target, callerService)) {
            is RecordBreakGlassOutcome.Recorded -> {
                ResponseEntity.status(HttpStatus.CREATED).body(
                    BreakGlassActionResponse(
                        actionRef = outcome.actionRef,
                        unlockRef = unlockRef,
                        action = BreakGlassActionResponse.Action.valueOf(action.name),
                    ),
                )
            }

            RecordBreakGlassOutcome.NotFound -> {
                throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
            }

            RecordBreakGlassOutcome.NotOpen -> {
                throw ApiConflict("the break-glass session is not open")
            }
        }
    }

    private fun unlocked(session: BreakGlassSession) =
        BreakGlassResponse(
            unlockRef = session.ref,
            tenantRef = session.tenantRef,
            status = BreakGlassResponse.Status.UNLOCKED,
            sessionHours = session.sessionHours,
            expiresAt = session.expiresAt.atOffset(ZoneOffset.UTC),
            permittedActions =
                BreakGlassRules.PERMITTED_ACTIONS.map { BreakGlassResponse.PermittedActions.valueOf(it.name) }.toSet(),
        )
}
