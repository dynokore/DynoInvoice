package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.breakglass.ApproveBreakGlassUnlockOutcome
import com.dynokore.gst.access.application.breakglass.ApproveBreakGlassUnlockUseCase
import com.dynokore.gst.access.application.breakglass.BreakGlassSession
import com.dynokore.gst.access.application.breakglass.RecordBreakGlassActionUseCase
import com.dynokore.gst.access.application.breakglass.RecordBreakGlassOutcome
import com.dynokore.gst.access.application.breakglass.ResealBreakGlassOutcome
import com.dynokore.gst.access.application.breakglass.ResealBreakGlassUseCase
import com.dynokore.gst.access.application.breakglass.SignInWithBackoffOutcome
import com.dynokore.gst.access.application.breakglass.SignInWithBackoffUseCase
import com.dynokore.gst.access.application.breakglass.UnlockBreakGlassOutcome
import com.dynokore.gst.access.application.breakglass.UnlockBreakGlassUseCase
import com.dynokore.gst.access.contract.api.BreakGlassApi
import com.dynokore.gst.access.contract.model.ApproveBreakGlassUnlockRequest
import com.dynokore.gst.access.contract.model.ApproveBreakGlassUnlockResponse
import com.dynokore.gst.access.contract.model.BreakGlassActionRequest
import com.dynokore.gst.access.contract.model.BreakGlassActionResponse
import com.dynokore.gst.access.contract.model.BreakGlassResealResponse
import com.dynokore.gst.access.contract.model.BreakGlassResponse
import com.dynokore.gst.access.contract.model.BreakGlassSessionResponse
import com.dynokore.gst.access.contract.model.BreakGlassSignInRequest
import com.dynokore.gst.access.contract.model.UnlockBreakGlassRequest
import com.dynokore.gst.access.domain.breakglass.BreakGlassAction
import com.dynokore.gst.access.domain.breakglass.BreakGlassRules
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.serviceruntime.identity.ServiceCaller
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.time.ZoneOffset
import java.util.UUID

/** The collaborators of `signInBreakGlass` and `resealBreakGlass` (INV-T-8853). */
class BreakGlassSealing(
    val signIn: SignInWithBackoffUseCase,
    val reseal: ResealBreakGlassUseCase,
    val permission: ResealPermissionCheck,
    val replay: BreakGlassSignInReplay,
    val mapper: ObjectMapper,
)

/**
 * TPL-08. unlockBreakGlass, approveBreakGlassUnlock and recordBreakGlassAction (INV-REQ-896, INV-REQ-897,
 * INV-REQ-1269): the unlock is for the bound tenant only; fewer than two different people is refused (400,
 * SRT-001) and a pair that does not hold the roles is refused (403). The session length is never a request field:
 * the 201 carries the VP-08 value, the expiry and the four permitted actions. An unlock while the identity provider
 * counts as available needs the override reason (409). Recording on a session that is resealed or past its expiry
 * is 409. The contract lists no error code for 409, so the problem body carries a detail that says what is wrong and
 * no catalogue code (INV-Q-8845 A).
 * signInBreakGlass (INV-T-8853, INV-T-13465) answers 429 (SRT-012, Retry-After) while the back-off runs, 404 for an
 * unknown unlock, 409 (no code) when the unlock is resealed or
 * past its expiry, 403 for a wrong credential or a tenant with no account row, and 200 otherwise.
 * resealBreakGlass needs the permission `break-glass.reseal` (403 otherwise, nothing changes), is 409 (no code)
 * after the expiry, and carries the new
 * `sealingCredential` only in the 200 of the call that rotated it (INV-AC-8900); a second call answers the data alone.
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin in the
// template)
open class BreakGlassController(
    private val unlock: UnlockBreakGlassUseCase,
    private val approve: ApproveBreakGlassUnlockUseCase,
    private val record: RecordBreakGlassActionUseCase,
    private val tenants: TenantContext,
    private val serviceCaller: ServiceCaller,
    private val sealing: BreakGlassSealing,
) : BreakGlassApi {
    override fun unlockBreakGlass(
        idempotencyKey: UUID,
        unlockBreakGlassRequest: UnlockBreakGlassRequest,
    ): ResponseEntity<BreakGlassResponse> {
        val tenant = tenants.current().toString()
        // an unlock is for the caller's own tenant only
        requireOwnTenant(unlockBreakGlassRequest.tenantRef, tenant)
        val approvers = unlockBreakGlassRequest.approvedBy.map { GrantRequests.identityOf(it) }
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound" }
        return when (
            val outcome =
                unlock.handle(
                    actor,
                    TenantRef(tenant),
                    approvers,
                    unlockBreakGlassRequest.overrideReason?.value,
                )
        ) {
            is UnlockBreakGlassOutcome.Unlocked -> {
                ResponseEntity.status(HttpStatus.CREATED).body(unlocked(outcome.session))
            }

            UnlockBreakGlassOutcome.PairOfOne -> {
                GrantRequests.invalid("break-glass needs two different people: a pair of one person is refused")
            }

            UnlockBreakGlassOutcome.PairNotHeld -> {
                deny("the pair does not hold the roles of the break-glass setting")
            }

            UnlockBreakGlassOutcome.ApprovalMissing -> {
                deny("a named approver has no approval within the window")
            }

            UnlockBreakGlassOutcome.IdpAvailable -> {
                throw ApiConflict(
                    "the identity provider is available: the unlock needs the override reason IDP_PARTIAL_OUTAGE",
                )
            }
        }
    }

    override fun approveBreakGlassUnlock(
        idempotencyKey: UUID,
        approveBreakGlassUnlockRequest: ApproveBreakGlassUnlockRequest,
    ): ResponseEntity<ApproveBreakGlassUnlockResponse> {
        val tenant = tenants.current().toString()
        // an approval is for the caller's own tenant only
        requireOwnTenant(approveBreakGlassUnlockRequest.tenantRef, tenant)
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound" }
        return when (val outcome = approve.handle(actor, TenantRef(tenant), idempotencyKey)) {
            is ApproveBreakGlassUnlockOutcome.Approved -> {
                ResponseEntity.status(HttpStatus.CREATED).body(
                    ApproveBreakGlassUnlockResponse(
                        approvalRef = outcome.approval.ref,
                        tenantRef = outcome.approval.tenantRef,
                        madeAt = outcome.approval.madeAt.atOffset(ZoneOffset.UTC),
                        expiresAt = outcome.approval.expiresAt.atOffset(ZoneOffset.UTC),
                    ),
                )
            }

            ApproveBreakGlassUnlockOutcome.NotPermitted -> {
                throw ApiRefusal(
                    ClosedErrorCode.ACCESS_DENIED,
                    "the caller may not approve a break-glass unlock",
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
        val callerService = verifiedCaller()
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound" }
        val action = BreakGlassAction.valueOf(breakGlassActionRequest.action.name)
        return when (
            val outcome =
                record.handle(
                    actor,
                    unlockRef,
                    action,
                    breakGlassActionRequest.target,
                    callerService,
                )
        ) {
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

    override fun signInBreakGlass(
        unlockRef: String,
        idempotencyKey: UUID,
        breakGlassSignInRequest: BreakGlassSignInRequest,
    ): ResponseEntity<BreakGlassSessionResponse> {
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound" }
        val stored =
            sealing.replay.once(sealing.signIn, actor, unlockRef, idempotencyKey, breakGlassSignInRequest) { outcome ->
                signInReply(outcome)
            }
        // rebuilt outside the transaction: a refusal thrown inside it would roll the counter back
        return when (stored.status) {
            HttpStatus.OK.value() -> {
                ResponseEntity.ok(sealing.mapper.readValue(stored.body, BreakGlassSessionResponse::class.java))
            }

            HttpStatus.NOT_FOUND.value() -> {
                throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
            }

            HttpStatus.FORBIDDEN.value() -> {
                throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
            }

            HttpStatus.CONFLICT.value() -> {
                throw ApiConflict("the break-glass session is not open")
            }

            else -> {
                error("unexpected stored status ${stored.status}")
            }
        }
    }

    /** The reply stored under the key; the waiting back-off is thrown, so it is stored nowhere (429, Retry-After). */
    private fun signInReply(outcome: SignInWithBackoffOutcome): StoredResponse =
        when (outcome) {
            is SignInWithBackoffOutcome.SignedIn -> {
                val body =
                    BreakGlassSessionResponse(
                        unlockRef = outcome.session.ref,
                        tenantRef = outcome.session.tenantRef,
                        expiresAt = outcome.session.expiresAt.atOffset(ZoneOffset.UTC),
                        permittedActions =
                            BreakGlassRules.PERMITTED_ACTIONS
                                .map { BreakGlassSessionResponse.PermittedActions.valueOf(it.name) }
                                .toSet(),
                    )
                StoredResponse(HttpStatus.OK.value(), sealing.mapper.writeValueAsString(body))
            }

            SignInWithBackoffOutcome.NotFound -> {
                StoredResponse(HttpStatus.NOT_FOUND.value(), "")
            }

            SignInWithBackoffOutcome.NotOpen -> {
                StoredResponse(HttpStatus.CONFLICT.value(), "")
            }

            SignInWithBackoffOutcome.WrongCredential -> {
                StoredResponse(HttpStatus.FORBIDDEN.value(), "")
            }

            is SignInWithBackoffOutcome.Waiting -> {
                throw ApiRefusal(ClosedErrorCode.RATE_LIMITED, retryAfterSeconds = outcome.retryAfterSeconds)
            }
        }

    override fun resealBreakGlass(
        unlockRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<BreakGlassResealResponse> {
        // the permission of the operation (break-glass.reseal); checked before anything is read or written
        if (!sealing.permission.mayReseal()) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound" }
        return resealed(sealing.reseal.handle(actor, unlockRef))
    }

    private fun resealed(outcome: ResealBreakGlassOutcome): ResponseEntity<BreakGlassResealResponse> =
        when (outcome) {
            is ResealBreakGlassOutcome.Resealed -> {
                ResponseEntity.ok(sealed(outcome.session.ref, outcome.session.tenantRef, outcome.credential?.value))
            }

            is ResealBreakGlassOutcome.AlreadySealed -> {
                ResponseEntity.ok(sealed(outcome.session.ref, outcome.session.tenantRef, null))
            }

            ResealBreakGlassOutcome.NotFound -> {
                throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
            }

            ResealBreakGlassOutcome.NotOpen -> {
                throw ApiConflict("the break-glass session is not open")
            }
        }

    private fun sealed(
        unlockRef: String,
        tenantRef: String,
        credential: String?,
    ) = BreakGlassResealResponse(unlockRef, tenantRef, BreakGlassResealResponse.Status.SEALED, credential)

    private fun requireOwnTenant(
        requested: String,
        bound: String,
    ) {
        if (requested != bound) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
    }

    private fun deny(detail: String): Nothing = throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED, detail)

    private fun verifiedCaller(): String = serviceCaller.current() ?: throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)

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
