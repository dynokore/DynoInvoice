package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.authorise.Authorise
import com.dynokore.gst.access.application.authorise.MachineAuthorise
import com.dynokore.gst.access.application.authorise.MachineDecision
import com.dynokore.gst.access.application.keys.RevocationList
import com.dynokore.gst.access.application.keys.RevocationListOutcome
import com.dynokore.gst.access.application.keys.VerificationKeys
import com.dynokore.gst.access.contract.api.AuthoriseApi
import com.dynokore.gst.access.contract.model.AuthoriseResponse
import com.dynokore.gst.access.contract.model.RevocationEntry
import com.dynokore.gst.access.contract.model.RevocationListResponse
import com.dynokore.gst.access.contract.model.VerificationKeysResponse
import com.dynokore.gst.access.contract.model.VerificationKeysResponseKeysInner
import com.dynokore.gst.access.domain.grant.Decision
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Target
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import java.util.Base64

/**
 * TPL-08. `authorise` answers 200 whatever the decision: a DENY, not an HTTP error, carries a refusal (the operation's
 * contract). The gateway operations answer only the gateway's service account (API-25; 403 SRT-004 otherwise).
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin in the
// template)
open class AuthoriseController(
    private val authorise: Authorise,
    private val machine: MachineAuthorise,
    private val keys: VerificationKeys,
    private val revocations: RevocationList,
    private val gateway: GatewayCaller,
) : AuthoriseApi {
    override fun authorise(
        principalRef: String,
        permission: String,
        scope: String,
    ): ResponseEntity<AuthoriseResponse> {
        if (principalRef.startsWith(
                PRINCIPAL_PREFIX,
            )
        ) {
            return ResponseEntity.ok(decideMachine(principalRef, permission, scope))
        }
        val decision = decide(principalRef, permission, scope)
        val body =
            when (decision) {
                is Decision.Allow -> AuthoriseResponse(AuthoriseResponse.Decision.ALLOW, role = decision.role.wire)
                is Decision.Deny -> AuthoriseResponse(AuthoriseResponse.Decision.DENY, reasonCode = decision.reasonCode)
            }
        return ResponseEntity.ok(body)
    }

    /** A service principal (`PRN-...`) is decided by the fixed permission set of its type, not by grants. */
    private fun decideMachine(
        principalRef: String,
        permission: String,
        scope: String,
    ): AuthoriseResponse {
        val target = targetOf(scope) ?: return deny(BAD_SCOPE)
        return when (val decision = machine.handle(principalRef, permission, target.entity)) {
            MachineDecision.Allow -> AuthoriseResponse(AuthoriseResponse.Decision.ALLOW)
            is MachineDecision.Deny -> deny(decision.reasonCode)
        }
    }

    private fun deny(reason: String) = AuthoriseResponse(AuthoriseResponse.Decision.DENY, reasonCode = reason)

    private fun decide(
        principalRef: String,
        permission: String,
        scope: String,
    ): Decision {
        val target = targetOf(scope)
        val principal = runCatching { IdentityRef(principalRef) }.getOrNull()
        val asked = runCatching { Permission(permission) }.getOrNull()
        return when {
            target == null -> Decision.Deny(BAD_SCOPE)
            principal == null -> Decision.Deny(BAD_PRINCIPAL)
            asked == null -> Decision.Deny(BAD_PERMISSION)
            else -> authorise.handle(principal, asked, target)
        }
    }

    /** `entity:<ref>` only: the contract defines no other form of the question's scope. */
    private fun targetOf(scope: String): Target? {
        if (!scope.startsWith(ENTITY_PREFIX)) return null
        val ref = scope.removePrefix(ENTITY_PREFIX)
        return runCatching { Target(EntityRef(ref)) }.getOrNull()
    }

    override fun getVerificationKeys(): ResponseEntity<VerificationKeysResponse> {
        requireGateway()
        val body =
            VerificationKeysResponse(
                keys.handle().map {
                    VerificationKeysResponseKeysInner(
                        it.kid,
                        it.alg,
                        Base64.getDecoder().decode(it.publicKey),
                    )
                },
            )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
    }

    override fun getRevocationList(
        sinceVersion: Long?,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<RevocationListResponse> {
        requireGateway()
        val page =
            when (val outcome = revocations.handle(sinceVersion, limit, cursor)) {
                is RevocationListOutcome.Page -> outcome

                RevocationListOutcome.MalformedCursor -> throw ApiRefusal(
                    ClosedErrorCode.CONTRACT_VIOLATION,
                    "cursor is malformed",
                )
            }
        val body =
            RevocationListResponse(
                listVersion = page.listVersion,
                snapshot = page.snapshot,
                items = page.items.map { entryOf(it) },
                nextCursor = page.nextCursor,
            )
        return ResponseEntity
            .ok()
            .eTag("\"${page.listVersion}\"")
            .cacheControl(CacheControl.noStore())
            .body(body)
    }

    private fun requireGateway() {
        if (!gateway.isGateway()) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
    }

    private fun entryOf(entry: com.dynokore.gst.access.domain.revocation.RevocationEntry) =
        RevocationEntry(
            entryId = entry.entryId,
            version = entry.version,
            kind = RevocationEntry.Kind.valueOf(entry.kind.name),
            revokedAt = entry.revokedAt.atOffset(ZoneOffset.UTC),
            expiresAt = entry.expiresAt.atOffset(ZoneOffset.UTC),
            principalRef = entry.principalRef,
            subject = entry.subject,
            tokenId = entry.tokenId,
        )

    private companion object {
        const val ENTITY_PREFIX = "entity:"
        const val PRINCIPAL_PREFIX = "PRN-"
        const val BAD_SCOPE = "BAD_SCOPE"
        const val BAD_PRINCIPAL = "BAD_PRINCIPAL"
        const val BAD_PERMISSION = "BAD_PERMISSION"
    }
}
