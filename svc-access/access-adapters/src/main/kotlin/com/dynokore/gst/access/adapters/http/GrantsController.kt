package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.grant.GrantRoleCommand
import com.dynokore.gst.access.application.grant.GrantRoleOutcome
import com.dynokore.gst.access.application.grant.GrantRoleUseCase
import com.dynokore.gst.access.application.grant.RevokeGrantOutcome
import com.dynokore.gst.access.application.grant.RevokeGrantUseCase
import com.dynokore.gst.access.contract.api.GrantsApi
import com.dynokore.gst.access.contract.model.GrantRequest
import com.dynokore.gst.access.contract.model.GrantResponse
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import java.util.UUID

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
) : GrantsApi {
    override fun grantRole(
        idempotencyKey: UUID,
        grantRequest: GrantRequest,
    ): ResponseEntity<GrantResponse> {
        requireManager()
        val tenant = TenantRef(tenants.current().toString())
        val scope = scopeOf(grantRequest.scope, tenant)
        val identity = identityOf(grantRequest.identityRef)
        val window = windowOf(grantRequest)
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
                invalid("a grant without a scope is invalid")
            }

            is GrantRoleOutcome.UnknownRole -> {
                invalid("role is not in the role catalogue")
            }
        }
    }

    private fun scopeOf(
        text: String,
        tenant: TenantRef,
    ): Scope? =
        when (val parsed = ScopeParser.parse(text, tenant)) {
            is ScopeParser.Parsed.Ok -> parsed.scope
            ScopeParser.Parsed.Missing -> null
            is ScopeParser.Parsed.Unsupported -> invalid("scope is not supported")
        }

    private fun identityOf(text: String): IdentityRef =
        runCatching { IdentityRef(text) }.getOrElse { invalid("identityRef is invalid") }

    private fun windowOf(request: GrantRequest): ValidityWindow =
        runCatching {
            ValidityWindow(
                request.validFrom.atStartOfDay(ZoneOffset.UTC).toInstant(),
                request.validTo?.atStartOfDay(ZoneOffset.UTC)?.toInstant(),
            )
        }.getOrElse { invalid("validTo must be after validFrom") }

    private fun invalid(message: String): Nothing = throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, message)

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
