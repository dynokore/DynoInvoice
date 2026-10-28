package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.grant.GrantRoleCommand
import com.dynokore.gst.access.application.grant.GrantRoleOutcome
import com.dynokore.gst.access.application.grant.GrantRoleUseCase
import com.dynokore.gst.access.application.grant.RevokeGrantOutcome
import com.dynokore.gst.access.application.grant.RevokeGrantUseCase
import com.dynokore.gst.access.application.landing.ChooseLandingOutcome
import com.dynokore.gst.access.application.landing.LandingScreens
import com.dynokore.gst.access.contract.api.GrantsApi
import com.dynokore.gst.access.contract.model.GrantRequest
import com.dynokore.gst.access.contract.model.GrantResponse
import com.dynokore.gst.access.contract.model.LandingChoiceRequest
import com.dynokore.gst.access.contract.model.LandingScreenResponse
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Landing
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import java.util.UUID

/** The detail of the 403 for an Operator role in grantRole and mapIdpGroup (INV-Q-9069 A). */
internal const val CONFLICTING_PAIR_DETAIL = "these two roles cannot be held by the same person in this tenant"
internal const val OPERATOR_ROLE_REFUSED_DETAIL = "an operator role cannot be granted in a customer tenant"

/**
 * TPL-08. grantRole and revokeGrant: the caller must be allowed to manage grants ([GrantManagerCheck]); a grant without
 * a
 * scope, with an unknown role or with a scope this version cannot read is refused with 400 (SRT-001); a repeat of the
 * same
 * identity, role and scope returns the original grant (repeatKey of the contract).
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin in the
// template)
open class GrantsController(
    private val grantRole: GrantRoleUseCase,
    private val revokeGrant: RevokeGrantUseCase,
    private val tenants: TenantContext,
    private val manager: GrantManagerCheck,
    private val landing: LandingScreens,
) : GrantsApi {
    override fun grantRole(
        idempotencyKey: UUID,
        grantRequest: GrantRequest,
    ): ResponseEntity<GrantResponse> {
        requireManager()
        val tenant = TenantRef(tenants.current().toString())
        val scope = GrantRequests.scopeOf(grantRequest.scope, tenant)
        val identity = GrantRequests.identityOf(grantRequest.identityRef)
        val window = GrantRequests.windowOf(grantRequest)
        val command =
            GrantRoleCommand(
                tenant = tenant,
                actor = checkNotNull(tenants.currentActor()) { "no actor is bound" },
                identity = identity,
                granteeKind = GranteeKind.valueOf(grantRequest.granteeKind.name),
                roleWire = grantRequest.role,
                scope = scope,
                window = window,
            )
        return when (val outcome = grantRole.handle(command)) {
            is GrantRoleOutcome.Granted -> {
                ResponseEntity
                    .status(
                        if (outcome.repeated) HttpStatus.OK else HttpStatus.CREATED,
                    ).body(responseOf(outcome.grant, grantRequest.scope))
            }

            GrantRoleOutcome.ScopeMissing -> {
                GrantRequests.invalid("a grant without a scope is invalid")
            }

            is GrantRoleOutcome.UnknownRole -> {
                GrantRequests.invalid("role is not in the role catalogue")
            }

            // the contract lists 403 and 409 for grantRole; no catalogue error code names these refusals (INV-REQ-891)
            GrantRoleOutcome.SecondHolderRefused,
            GrantRoleOutcome.SoleOperatorAckRequired,
            GrantRoleOutcome.ConflictingPairRefused,
            GrantRoleOutcome.OperatorRoleRefused,
            -> {
                throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED, refusalDetail(outcome))
            }
        }
    }

    private fun refusalDetail(outcome: GrantRoleOutcome) =
        when (outcome) {
            GrantRoleOutcome.SecondHolderRefused -> {
                "only one person may hold security.officer and tenant.admin under the sole-operator acknowledgement"
            }

            GrantRoleOutcome.ConflictingPairRefused -> {
                CONFLICTING_PAIR_DETAIL
            }

            GrantRoleOutcome.OperatorRoleRefused -> {
                OPERATOR_ROLE_REFUSED_DETAIL
            }

            else -> {
                "security.officer and tenant.admin need the recorded sole-operator acknowledgement"
            }
        }

    override fun revokeGrant(
        grantRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<Unit> {
        requireManager()
        val ref = runCatching { GrantRef(grantRef) }.getOrElse { throw ApiRefusal(ClosedErrorCode.NOT_FOUND) }
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound" }
        return when (revokeGrant.handle(actor, ref)) {
            is RevokeGrantOutcome.Revoked, is RevokeGrantOutcome.AlreadyRevoked -> ResponseEntity.noContent().build()
            RevokeGrantOutcome.NotFound -> throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
        }
    }

    override fun chooseLandingScreen(
        idempotencyKey: UUID,
        landingChoiceRequest: LandingChoiceRequest,
    ): ResponseEntity<LandingScreenResponse> =
        when (val outcome = landing.choose(caller(), landingChoiceRequest.role)) {
            is ChooseLandingOutcome.Chosen -> ResponseEntity.ok(landingOf(outcome.landing))
            ChooseLandingOutcome.NotHeld -> throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        }

    override fun clearLandingChoice(idempotencyKey: UUID): ResponseEntity<Unit> {
        landing.clear(caller())
        return ResponseEntity.noContent().build()
    }

    override fun resolveLandingScreen(): ResponseEntity<LandingScreenResponse> =
        ResponseEntity.ok(landingOf(landing.resolve(caller()) ?: throw ApiRefusal(ClosedErrorCode.NOT_FOUND)))

    /** The person is the authenticated subject of the call, never a request field (INV-REQ-2756). */
    private fun caller(): IdentityRef =
        GrantRequests.identityOf(
            checkNotNull(tenants.currentActor()) {
                "no actor is bound"
            },
        )

    private fun landingOf(landing: Landing) =
        LandingScreenResponse(landing.role.wire, LandingScreenResponse.Source.valueOf(landing.source.name))

    private fun requireManager() {
        if (!manager.mayManageGrants()) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
    }

    private fun responseOf(
        grant: Grant,
        scopeText: String,
    ) = GrantResponse(
        grantRef = grant.ref.value,
        identityRef = grant.identity.value,
        role = grant.role.wire,
        scope = scopeText,
        status =
            GrantResponse.Status.valueOf(
                if (grant.status ==
                    GrantStatus.REVOKED
                ) {
                    "REVOKED"
                } else {
                    grant.status.name
                },
            ),
        grantSource = GrantResponse.GrantSource.valueOf(grant.source.name),
    )
}
