package com.dynokore.gst.controlPlane.adapters.http

import com.dynokore.gst.controlPlane.adapters.bundle.CanonicalJson
import com.dynokore.gst.controlPlane.application.pack.AuthorRegulatoryPack
import com.dynokore.gst.controlPlane.application.pack.AuthorRegulatoryPackCommand
import com.dynokore.gst.controlPlane.application.pack.GetPack
import com.dynokore.gst.controlPlane.application.pack.OperatorRole
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.pack.PublishPack
import com.dynokore.gst.controlPlane.application.pack.PublishPackCommand
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
import com.dynokore.gst.controlPlane.domain.pack.PackContents
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.pack.PackStatus
import com.dynokore.gst.controlPlane.domain.pack.PackType
import com.dynokore.gst.controlPlane.domain.pack.PackVersion
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.operation.OperationOutcome
import com.dynokore.platform.serviceruntime.operation.OperationRunner
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * authorRegulatoryPack, signPack, publishPack and getPack (TPL-08). The other operations of the contract are not
 * overridden here, so the generated interface answers 501 for them; `operations.tsv` lists each under its own task.
 *
 * The caller is the verified token's `sub`, never a request field (API-24); a pack is platform data, so the command
 * runs in a transaction bound to the reserved platform tenant, by an operator token that has no tenant.
 */
@RestController
@Suppress("LongParameterList") // one constructor-injected controller; each parameter is one collaborator (TPL-18)
class PacksController(
    private val principals: EndUserPrincipalResolver,
    private val platform: PlatformTenantBinding,
    private val transactions: TransactionRunner,
    private val runner: OperationRunner,
    private val roles: OperatorRoles,
    private val author: AuthorRegulatoryPack,
    private val sign: SignPack,
    private val publish: PublishPack,
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
                    authorPackRequest.effectiveFrom,
                    authorPackRequest.contents?.let { PackContents(CanonicalJson.text(it)) },
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

    override fun publishPack(
        packRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<PackResponse> {
        val caller = caller()
        val command =
            commandOf(caller) {
                PublishPackCommand(
                    ActorId(caller.principal.actor),
                    PackRef(packRef),
                    RequestContext(caller.correlationId, idempotencyKey.toString()),
                )
            }
        val outcome =
            platform.bindPlatform(
                caller.principal.actor,
            ) { runner.run(publish, caller.principal.actor, command) }
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

    private fun caller() = OperatorCaller.of(principals)

    private fun <T> commandOf(
        caller: OperatorCaller,
        build: () -> T,
    ): T = caller.commandOf(build)

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
                problem(ClosedErrorCode.CONTRACT_VIOLATION, correlationId, "signingKeyRef is blank or unknown")
            }

            // a precondition of the operation is unmet: the detail names the missing or mismatched value or record
            Refusal.EFFECTIVE_FROM_MISSING,
            Refusal.CONTENTS_MISSING,
            Refusal.NOT_SIGNED,
            Refusal.MIN_ENGINE_VERSION_MISSING,
            Refusal.NO_STAGING_RECORD,
            Refusal.STAGING_NOT_PASSED,
            Refusal.STAGING_HASH_MISMATCH,
            -> {
                problem(ClosedErrorCode.CONTRACT_VIOLATION, correlationId, "${refusal.code}: ${refusal.detail}")
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
            effectiveFrom = pack.effectiveFrom,
            supersedes = pack.supersedes?.value,
        ).also { check(pack.status != PackStatus.PUBLISHED || pack.publishedAt != null) }
}
