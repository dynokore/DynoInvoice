package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.adapters.packs.EngineVersion
import com.dynokore.gst.rules.adapters.persistence.JooqMappingPackStore
import com.dynokore.gst.rules.adapters.web.MappingPackMapping.activationOf
import com.dynokore.gst.rules.adapters.web.MappingPackMapping.approvalOf
import com.dynokore.gst.rules.adapters.web.MappingPackMapping.entryOf
import com.dynokore.gst.rules.adapters.web.MappingPackMapping.problemBody
import com.dynokore.gst.rules.adapters.web.MappingPackMapping.refusalOf
import com.dynokore.gst.rules.adapters.web.MappingPackMapping.statusOf
import com.dynokore.gst.rules.application.AcceptEntryCommand
import com.dynokore.gst.rules.application.ActivateCommand
import com.dynokore.gst.rules.application.ActivePacksOutcome
import com.dynokore.gst.rules.application.DraftCommand
import com.dynokore.gst.rules.application.ListActiveRegulatoryPacks
import com.dynokore.gst.rules.application.MapTaxCodeCommand
import com.dynokore.gst.rules.application.MappingPackAuthoring
import com.dynokore.gst.rules.application.MappingPackResult
import com.dynokore.gst.rules.application.PackActionCommand
import com.dynokore.gst.rules.application.RefusalReason
import com.dynokore.gst.rules.contract.api.MappingPacksApi
import com.dynokore.gst.rules.contract.api.RegulatoryPacksApi
import com.dynokore.gst.rules.contract.model.AcceptSourceClassEntryRequest
import com.dynokore.gst.rules.contract.model.ActivateMappingPackRequest
import com.dynokore.gst.rules.contract.model.ActiveRegulatoryPack
import com.dynokore.gst.rules.contract.model.ActiveRegulatoryPackPage
import com.dynokore.gst.rules.contract.model.DraftMappingPackRequest
import com.dynokore.gst.rules.contract.model.DryRunRequest
import com.dynokore.gst.rules.contract.model.DryRunResult
import com.dynokore.gst.rules.contract.model.MapTaxCodeRequest
import com.dynokore.gst.rules.contract.model.MappingEntry
import com.dynokore.gst.rules.contract.model.MappingEntryPage
import com.dynokore.gst.rules.contract.model.MappingPack
import com.dynokore.gst.rules.contract.model.RulesDryRunStatus
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.gst.rules.domain.pack.SourceClass
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The mapping pack and regulatory pack APIs of the rules contract (TPL-08); 501 until the task that implements an
 * operation (operations.tsv) overrides it.
 *
 * The contract tags `dryRun` and `getDryRun` with both tags, so the generator puts them in both interfaces with the
 * same
 * mapping. One controller must therefore implement both interfaces (two controllers would map the same path twice and
 * Spring MVC refuses to start), and Kotlin requires it to restate those two operations. They are stated here as the
 * explicit 501 and nowhere else; the operations gate (operations.tsv) reads such a body as a stub.
 */
@Suppress("TooManyFunctions") // one handler per operation of the two generated interfaces, 19 operations in all
@RestController
open class PacksController(
    private val listActive: ListActiveRegulatoryPacks,
    private val authoring: MappingPackAuthoring,
    private val packs: JooqMappingPackStore,
    private val tenants: TenantContext,
    private val transactions: TransactionRunner,
    private val engineVersion: EngineVersion,
) : MappingPacksApi,
    RegulatoryPacksApi {
    /** The armed regulatory packs of the platform layer for the installer; the caller check is the generated one. */
    override fun listActiveRegulatoryPacks(
        limit: Int,
        cursor: String?,
    ): ResponseEntity<ActiveRegulatoryPackPage> =
        when (val outcome = listActive.handle(limit, cursor)) {
            is ActivePacksOutcome.Page -> {
                ResponseEntity.ok(
                    ActiveRegulatoryPackPage(
                        items =
                            outcome.items.map {
                                ActiveRegulatoryPack(
                                    packType = it.packType,
                                    version = it.version,
                                    status = ActiveRegulatoryPack.Status.forValue(it.status),
                                    minEngineVersion = it.minEngineVersion,
                                    effectiveFrom = it.effectiveFrom,
                                    effectiveTo = it.effectiveTo,
                                )
                            },
                        nextCursor = outcome.nextCursor,
                    ),
                )
            }

            ActivePacksOutcome.MalformedCursor -> {
                throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "cursor is malformed")
            }

            ActivePacksOutcome.InvalidLimit -> {
                throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "limit must be 1..500")
            }
        }

    // ---- the mapping pack authoring and sign-off operations (INV-T-4972) ----

    /** A refusal of a mapping pack command or read as the API-20 envelope. */
    @ExceptionHandler(MappingRefusal::class)
    fun refuse(refusal: MappingRefusal): ResponseEntity<String> =
        ResponseEntity
            .status(refusal.status)
            .header("Content-Type", "application/problem+json")
            .body(problemBody(refusal, correlationId()))

    override fun draftMappingPack(
        idempotencyKey: UUID,
        draftMappingPackRequest: DraftMappingPackRequest,
    ): ResponseEntity<MappingPack> =
        transactions.inTransaction {
            val label = draftMappingPackRequest.label
            if (packs.labelTaken(label)) throw conflictOf(RefusalReason.INPUT_INVALID)
            val packId = newPackId()
            val result =
                authoring.draft(
                    DraftCommand(
                        actor(),
                        draftMappingPackRequest.entityRef.orEmpty(),
                        packId,
                        tenants.current(),
                        label,
                    ),
                )
            done(result)
            ResponseEntity.status(HttpStatus.CREATED).body(packAnswer(packId, withApprovals = false))
        }

    override fun getMappingPack(
        packId: String,
        entityRef: String?,
    ): ResponseEntity<MappingPack> =
        transactions.inTransaction {
            sameEntity(packId, entityRef)
            ResponseEntity.ok(packAnswer(packId, withApprovals = true, withCounts = true))
        }

    override fun listMappingPackEntries(
        packId: String,
        entityRef: String?,
        limit: Int,
        cursor: String?,
        sourceClass: String?,
        accepted: Boolean?,
    ): ResponseEntity<MappingEntryPage> =
        transactions.inTransaction {
            sameEntity(packId, entityRef)
            val wanted = sourceClass?.let { sourceClassOrRefuse(it) }
            val rows = packs.entryViews(packId, cursor, limit + 1, wanted, accepted)
            val page = rows.take(limit).map(::entryOf)
            ResponseEntity.ok(MappingEntryPage(page, if (rows.size > limit) page.last().entryId else null))
        }

    override fun mapTaxCode(
        packId: String,
        idempotencyKey: UUID,
        mapTaxCodeRequest: MapTaxCodeRequest,
    ): ResponseEntity<MappingEntry> =
        transactions.inTransaction {
            val request = mapTaxCodeRequest
            done(
                authoring.mapTaxCode(
                    MapTaxCodeCommand(
                        actor(),
                        request.entityRef.orEmpty(),
                        packId,
                        request.internalTaxCode,
                        request.irasCode,
                        sourceClassOrRefuse(request.sourceClass.value),
                    ),
                ),
            )
            ResponseEntity.status(HttpStatus.CREATED).body(entryAnswer(packId, request.internalTaxCode))
        }

    override fun acceptSourceClassEntry(
        packId: String,
        entryId: String,
        idempotencyKey: UUID,
        acceptSourceClassEntryRequest: AcceptSourceClassEntryRequest,
    ): ResponseEntity<MappingEntry> =
        transactions.inTransaction {
            val request = acceptSourceClassEntryRequest
            done(
                authoring.acceptEntry(
                    AcceptEntryCommand(
                        actor(),
                        request.entityRef.orEmpty(),
                        packId,
                        entryId,
                        request.rationale,
                        request.expiresOn,
                    ),
                ),
            )
            ResponseEntity.ok(entryAnswer(packId, entryId))
        }

    override fun submitForApproval(
        packId: String,
        idempotencyKey: UUID,
        entityRef: String?,
    ): ResponseEntity<MappingPack> =
        transactions.inTransaction {
            repeatable(authoring.submit(PackActionCommand(actor(), entityRef.orEmpty(), packId)), PackStatus.SUBMITTED)
            ResponseEntity.ok(packAnswer(packId, withApprovals = false))
        }

    override fun approvePack(
        packId: String,
        idempotencyKey: UUID,
        entityRef: String?,
    ): ResponseEntity<MappingPack> =
        transactions.inTransaction {
            repeatable(authoring.approve(PackActionCommand(actor(), entityRef.orEmpty(), packId)), PackStatus.APPROVED)
            ResponseEntity.ok(packAnswer(packId, withApprovals = true))
        }

    override fun activateMappingPack(
        packId: String,
        idempotencyKey: UUID,
        activateMappingPackRequest: ActivateMappingPackRequest,
    ): ResponseEntity<MappingPack> =
        transactions.inTransaction {
            val request = activateMappingPackRequest
            val minEngineVersion =
                engineVersion.current() ?: throw unavailable("the engine version of this build is unknown")
            repeatable(
                authoring.activate(
                    ActivateCommand(
                        actor(),
                        request.entityRef.orEmpty(),
                        packId,
                        request.effectiveFrom,
                        null,
                        minEngineVersion,
                    ),
                ),
                PackStatus.ARMED,
            )
            ResponseEntity.ok(packAnswer(packId, withApprovals = true, withActivation = true))
        }

    private fun correlationId(): String =
        CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()

    private fun actor(): String = checkNotNull(tenants.currentActor()) { "no actor is bound" }

    private fun done(result: MappingPackResult) {
        refusalOf(result)?.let { throw it }
    }

    /**
     * A repeat of a command whose pack already stands in the status the command leads to is a 200 with the current
     * state and stores nothing (contract `repeatKey`, INV-REQ-380). The use case checks the permission and the entity
     * before the lifecycle, so a repeat without the permission is still refused, and any other status stays a conflict.
     */
    private fun repeatable(
        result: MappingPackResult,
        target: PackStatus,
    ) {
        val repeat =
            result is MappingPackResult.Refused && result.reason == RefusalReason.LIFECYCLE && result.status == target
        if (!repeat) done(result)
    }

    private fun conflictOf(reason: RefusalReason) = checkNotNull(refusalOf(MappingPackResult.Refused(reason)))

    /** A request that names an entity other than the pack's is refused like a missing permission (contract text). */
    private fun sameEntity(
        packId: String,
        entityRef: String?,
    ) {
        val pack = packs.find(packId) ?: throw checkNotNull(refusalOf(MappingPackResult.NotFound))
        if (entityRef != null && entityRef != pack.entityRef) {
            throw checkNotNull(refusalOf(MappingPackResult.Forbidden(ErrorCode.SRT_004, "the action is not permitted")))
        }
    }

    private fun sourceClassOrRefuse(value: String): SourceClass =
        SourceClass.entries.firstOrNull { it.name == value }
            ?: throw conflictOf(RefusalReason.INPUT_INVALID)

    private fun entryAnswer(
        packId: String,
        entryId: String,
    ): MappingEntry =
        entryOf(
            checkNotNull(packs.entry(packId, entryId)) {
                "entry $entryId vanished inside its transaction"
            },
        )

    private fun packAnswer(
        packId: String,
        withApprovals: Boolean,
        withCounts: Boolean = false,
        withActivation: Boolean = false,
    ): MappingPack {
        val view = checkNotNull(packs.view(packId)) { "pack $packId vanished inside its transaction" }
        val approvals = if (withApprovals) packs.approvals(packId) else emptyList()
        val entityRef = packs.find(packId)?.entityRef
        return MappingPack(
            packId = packId,
            status = statusOf(view.status),
            entityRef = entityRef,
            effectiveFrom = view.effectiveFrom,
            entryCount = view.entryCount.takeIf { withCounts },
            nonPrimaryCount = view.nonPrimaryCount.takeIf { withCounts },
            unacceptedNonPrimaryCount = view.unacceptedNonPrimaryCount.takeIf { withCounts },
            packVersion = view.packVersion,
            approvals = approvals.map(::approvalOf).takeIf { it.isNotEmpty() },
            activation = activationOf(approvals).takeIf { withActivation || (withCounts && view.status.name == ARMED) },
        )
    }

    private fun newPackId(): String =
        "MAP-" +
            UUID
                .randomUUID()
                .toString()
                .take(PACK_ID_HEX)
                .uppercase()

    override fun dryRun(
        packRef: String,
        idempotencyKey: UUID,
        dryRunRequest: DryRunRequest?,
    ): ResponseEntity<DryRunResult> = ResponseEntity(HttpStatus.NOT_IMPLEMENTED)

    override fun getDryRun(
        packRef: String,
        dryRunId: String,
    ): ResponseEntity<RulesDryRunStatus> = ResponseEntity(HttpStatus.NOT_IMPLEMENTED)
}

private const val PACK_ID_HEX = 8
private const val ARMED = "ARMED"
