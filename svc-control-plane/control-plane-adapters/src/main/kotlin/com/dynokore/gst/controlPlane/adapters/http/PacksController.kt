package com.dynokore.gst.controlPlane.adapters.http

import com.dynokore.gst.controlPlane.application.pack.AuthorRegulatoryPack
import com.dynokore.gst.controlPlane.application.pack.AuthorRegulatoryPackCommand
import com.dynokore.gst.controlPlane.application.pack.GetPack
import com.dynokore.gst.controlPlane.application.pack.OperatorRole
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.pack.Refusal
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.pack.SignPack
import com.dynokore.gst.controlPlane.application.pack.SignPackCommand
import com.dynokore.gst.controlPlane.contract.api.PacksApi
import com.dynokore.gst.controlPlane.contract.model.AuthorPackRequest
import com.dynokore.gst.controlPlane.contract.model.PackResponse
import com.dynokore.gst.controlPlane.contract.model.SignPackRequest
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.pack.MinEngineVersion
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.pack.PackStatus
import com.dynokore.gst.controlPlane.domain.pack.PackType
import com.dynokore.gst.controlPlane.domain.pack.PackVersion
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.operation.OperationOutcome
import com.dynokore.platform.serviceruntime.operation.OperationRunner
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.UUID

/**
 * authorRegulatoryPack, signPack and getPack (TPL-08). publishPack and the other operations of the contract are not
 * overridden here, so the generated interface answers 501 for them; `operations.tsv` lists each as pending.
 *
 * The caller is the verified token's `sub`, never a request field (API-24); a pack is platform data, so the command
 * runs in a transaction bound to the reserved platform tenant, by an operator token that has no tenant.
 */
@RestController
class PacksController(
    private val principals: EndUserPrincipalResolver,
    private val platform: PlatformTenantBinding,
    private val transactions: TransactionRunner,
    private val runner: OperationRunner,
    private val roles: OperatorRoles,
    private val author: AuthorRegulatoryPack,
    private val sign: SignPack,
    private val get: GetPack,
) : PacksApi {
    override fun authorRegulatoryPack(
        idempotencyKey: UUID,
        authorPackRequest: AuthorPackRequest,
    ): ResponseEntity<PackResponse> {
        val caller = caller()
        val command =
            commandOf(caller) {
                AuthorRegulatoryPackCommand(
                    ActorId(caller.principal.actor),
                    PackType(authorPackRequest.packType),
                    PackVersion(authorPackRequest.version),
                    authorPackRequest.citations,
                    RequestContext(caller.correlationId, idempotencyKey.toString()),
                )
            }
        val outcome =
            platform.bindPlatform(
                caller.principal.actor,
            ) { runner.run(author, caller.principal.actor, command) }
        return ResponseEntity.status(HttpStatus.CREATED).body(answer(outcome, caller.correlationId))
    }

    override fun signPack(
        packRef: String,
        idempotencyKey: UUID,
        signPackRequest: SignPackRequest,
    ): ResponseEntity<PackResponse> {
        val caller = caller()
        val command =
            commandOf(caller) {
                SignPackCommand(
                    ActorId(caller.principal.actor),
                    PackRef(packRef),
                    signPackRequest.signingKeyRef,
                    signPackRequest.minEngineVersion?.let(::MinEngineVersion),
                    signPackRequest.exposureReducing ?: false,
                    RequestContext(caller.correlationId, idempotencyKey.toString()),
                )
            }
        val outcome =
            platform.bindPlatform(
                caller.principal.actor,
            ) { runner.run(sign, caller.principal.actor, command) }
        return ResponseEntity.ok(answer(outcome, caller.correlationId))
    }

    override fun getPack(packRef: String): ResponseEntity<PackResponse> {
        val caller = caller()
        val actor = caller.principal.actor
        if (!roles.holds(actor, OperatorRole.AUTHOR) && !roles.holds(actor, OperatorRole.APPROVER)) {
            problem(ClosedErrorCode.ACCESS_DENIED, caller.correlationId)
        }
        val ref = commandOf(caller) { PackRef(packRef) }
        val pack = platform.bindPlatform(actor) { transactions.inTransaction { get.get(ref) } }
        return ResponseEntity.ok(responseOf(pack ?: problem(ClosedErrorCode.NOT_FOUND, caller.correlationId)))
    }

    private class Caller(
        val principal: EndUserPrincipal,
        val correlationId: String,
    )

    /** The platform operator of the request: a verified token with no tenant (INV-REQ-1202 b2). */
    private fun caller(): Caller {
        val request = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request
        val correlationId =
            request.getHeader("X-Correlation-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val principal =
            principals.resolve(request.getHeader("Authorization"))
                ?: problem(ClosedErrorCode.UNAUTHENTICATED, correlationId)
        if (principal.tenant !=
            null
        ) {
            problem(ClosedErrorCode.ACCESS_DENIED, correlationId, "A platform operator token is required")
        }
        return Caller(principal, correlationId)
    }

    /** A request value the domain refuses (a malformed reference, a too long field) is a contract violation. */
    private fun <T> commandOf(
        caller: Caller,
        build: () -> T,
    ): T =
        try {
            build()
        } catch (invalid: IllegalArgumentException) {
            problem(ClosedErrorCode.CONTRACT_VIOLATION, caller.correlationId, invalid.message)
        }

    private fun answer(
        outcome: OperationOutcome<RegulatoryPack>,
        correlationId: String,
    ): PackResponse =
        when (outcome) {
            is OperationOutcome.Performed -> responseOf(outcome.result)
            is OperationOutcome.Repeated -> responseOf(outcome.result)
            OperationOutcome.NotPermitted -> problem(ClosedErrorCode.ACCESS_DENIED, correlationId)
            is OperationOutcome.PreconditionUnmet -> refuse(Refusal.parse(outcome.code), correlationId)
        }

    private fun refuse(
        refusal: Refusal,
        correlationId: String,
    ): Nothing =
        when (refusal.code) {
            Refusal.PACK_NOT_FOUND -> {
                problem(ClosedErrorCode.NOT_FOUND, correlationId)
            }

            Refusal.SEPARATION_OF_DUTY -> {
                problem(
                    ClosedErrorCode.ACCESS_DENIED,
                    correlationId,
                    "Separation of duties: the signer edited ${refusal.detail}; the version stays unsigned",
                )
            }

            Refusal.SIGNING_KEY_MISSING -> {
                problem(ClosedErrorCode.CONTRACT_VIOLATION, correlationId, "signingKeyRef is blank")
            }

            else -> {
                error("no response is declared for the refusal ${refusal.code}")
            }
        }

    private fun responseOf(pack: RegulatoryPack) =
        PackResponse(
            packRef = pack.ref.value,
            packType = pack.type.value,
            version = pack.version.value,
            status = PackResponse.Status.valueOf(pack.status.name),
            authors = pack.authors.map { it.value }.sorted(),
            signer = pack.signer?.value,
            minEngineVersion = pack.minEngineVersion?.value,
            exposureReducing = pack.exposureReducing,
        ).also { check(pack.status != PackStatus.PUBLISHED || pack.publishedAt != null) }
}
