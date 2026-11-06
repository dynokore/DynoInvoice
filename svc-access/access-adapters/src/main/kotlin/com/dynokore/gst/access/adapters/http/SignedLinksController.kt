package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.adapters.persistence.SignatoryEvidenceRefs
import com.dynokore.gst.access.application.link.LinkTenants
import com.dynokore.gst.access.application.link.RecordSignatoryActionUseCase
import com.dynokore.gst.access.application.link.SignatoryActionCommand
import com.dynokore.gst.access.application.link.SignatoryActionKind
import com.dynokore.gst.access.application.link.SignatoryActionOutcome
import com.dynokore.gst.access.application.link.VerifyLinkOutcome
import com.dynokore.gst.access.application.link.VerifySignatoryLinkUseCase
import com.dynokore.gst.access.contract.api.SignedLinksApi
import com.dynokore.gst.access.contract.model.SignatoryActionRequest
import com.dynokore.gst.access.contract.model.SignatoryActionResponse
import com.dynokore.gst.access.contract.model.SignatoryLinkVerifyResponse
import com.dynokore.gst.access.contract.model.VerifySignatoryLinkRequest
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.link.LinkRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * TPL-08. The operations a signed link's recipient calls (`x-caller-auth: signed-link`). The caller carries no end-user
 * token and no tenant, so the tenant is found from the link reference under the platform binding ([LinkTenants]) and
 * the work then runs with that tenant bound and the actor `link:<linkRef>`, never a role (INV-Q-9222 A). A reference
 * that is unknown is answered exactly as an expired link is: 401 SRT-003, so nothing is revealed (INV-REQ-899).
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin in the
// template)
open class SignedLinksController(
    private val verify: VerifySignatoryLinkUseCase,
    private val record: RecordSignatoryActionUseCase,
    private val linkTenants: LinkTenants,
    private val platform: PlatformTenantBinding,
    private val tenants: TenantContext,
) : SignedLinksApi {
    override fun verifySignatoryLink(
        linkRef: String,
        idempotencyKey: UUID,
        verifySignatoryLinkRequest: VerifySignatoryLinkRequest,
    ): ResponseEntity<SignatoryLinkVerifyResponse> {
        val ref = runCatching { LinkRef(linkRef) }.getOrNull() ?: throw refusalOf(VerifyLinkOutcome.Refused)
        val actor = VerifySignatoryLinkUseCase.actorOf(ref)
        val tenant = tenantOf(ref, actor)
        val outcome = tenants.bind(tenant, actor) { verify.handle(ref, verifySignatoryLinkRequest.passcode) }
        return when (outcome) {
            is VerifyLinkOutcome.Verified -> {
                ResponseEntity.ok(answer(outcome.ref, verified = true, outcome.wrongEntries, invalidated = false))
            }

            is VerifyLinkOutcome.Wrong -> {
                ResponseEntity.ok(answer(outcome.ref, verified = false, outcome.wrongEntries, invalidated = false))
            }

            is VerifyLinkOutcome.Invalidated -> {
                ResponseEntity.ok(answer(outcome.ref, verified = false, outcome.wrongEntries, invalidated = true))
            }

            VerifyLinkOutcome.Refused, VerifyLinkOutcome.Gone -> {
                throw refusalOf(outcome)
            }
        }
    }

    /**
     * `recordSignatoryAction`: the tenant is found from the link reference, as for the verification; the consent, the
     * client entity and the actor come from the link record and never from the request. Unknown, expired or
     * unverified: 401 SRT-003 (one answer for the three); invalidated or revoked: 410 ACC-004; a key the link's role
     * does not hold: 403; the consent not live: 404; an invalid body: 400.
     */
    override fun recordSignatoryAction(
        linkRef: String,
        idempotencyKey: UUID,
        signatoryActionRequest: SignatoryActionRequest,
    ): ResponseEntity<SignatoryActionResponse> {
        val ref = runCatching { LinkRef(linkRef) }.getOrNull() ?: throw ApiRefusal(ClosedErrorCode.UNAUTHENTICATED)
        val actor = VerifySignatoryLinkUseCase.actorOf(ref)
        val tenant = tenantOf(ref, actor)
        val command =
            SignatoryActionCommand(
                tenant = TenantRef(tenant.toString()),
                linkRef = ref,
                kind = SignatoryActionKind.valueOf(signatoryActionRequest.kind.name),
                packVersion = signatoryActionRequest.packVersion,
                queryText = signatoryActionRequest.queryText,
            )
        val outcome = tenants.bind(tenant, actor) { record.handle(command) }
        if (outcome !is SignatoryActionOutcome.Recorded) throw refusalOf(outcome)
        val action = outcome.action
        return ResponseEntity.status(HttpStatus.CREATED).body(
            SignatoryActionResponse(
                linkRef = action.linkRef.value,
                kind = SignatoryActionResponse.Kind.valueOf(action.kind.name),
                evidenceRef = SignatoryEvidenceRefs.refOf(tenant, action),
                // the stored value has the precision of the column, so a repeat answers the same instant
                recordedAt = action.recordedAt.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC),
            ),
        )
    }

    private fun refusalOf(outcome: SignatoryActionOutcome): RuntimeException =
        when (outcome) {
            SignatoryActionOutcome.Unauthenticated -> ApiRefusal(ClosedErrorCode.UNAUTHENTICATED)
            SignatoryActionOutcome.Invalidated -> ApiLinkInvalidated()
            SignatoryActionOutcome.Forbidden -> ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
            SignatoryActionOutcome.ConsentNotLive -> ApiRefusal(ClosedErrorCode.NOT_FOUND)
            is SignatoryActionOutcome.Invalid -> ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, outcome.reason)
            is SignatoryActionOutcome.Recorded -> IllegalStateException("a recorded action is no refusal")
        }

    /** Unknown or expired: 401 SRT-003; invalidated or revoked: 410 ACC-004. */
    private fun refusalOf(outcome: VerifyLinkOutcome): RuntimeException =
        if (outcome == VerifyLinkOutcome.Gone) ApiLinkInvalidated() else ApiRefusal(ClosedErrorCode.UNAUTHENTICATED)

    private fun tenantOf(
        ref: LinkRef,
        actor: String,
    ): TenantId =
        platform.bindPlatform(actor) { linkTenants.tenantOf(ref) } ?: throw ApiRefusal(ClosedErrorCode.UNAUTHENTICATED)

    private fun answer(
        ref: LinkRef,
        verified: Boolean,
        wrongEntries: Int,
        invalidated: Boolean,
    ) = SignatoryLinkVerifyResponse(
        linkRef = ref.value,
        verified = verified,
        wrongEntryCount = wrongEntries,
        invalidated = invalidated,
    )
}
