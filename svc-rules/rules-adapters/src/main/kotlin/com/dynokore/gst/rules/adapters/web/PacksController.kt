package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.adapters.packs.EngineVersion
import com.dynokore.gst.rules.adapters.persistence.IMPORT_REJECTED_CODE
import com.dynokore.gst.rules.adapters.persistence.JooqMappingImportRecordStore
import com.dynokore.gst.rules.adapters.persistence.JooqMappingPackStore
import com.dynokore.gst.rules.adapters.persistence.MappingImportRecordRow
import com.dynokore.gst.rules.adapters.web.MappingPackMapping.activationOf
import com.dynokore.gst.rules.adapters.web.MappingPackMapping.approvalOf
import com.dynokore.gst.rules.adapters.web.MappingPackMapping.entryOf
import com.dynokore.gst.rules.adapters.web.MappingPackMapping.problemBody
import com.dynokore.gst.rules.adapters.web.MappingPackMapping.refusalOf
import com.dynokore.gst.rules.adapters.web.MappingPackMapping.statusOf
import com.dynokore.gst.rules.application.AcceptEntryCommand
import com.dynokore.gst.rules.application.ActivateCommand
import com.dynokore.gst.rules.application.ActivePacksOutcome
import com.dynokore.gst.rules.application.CodeCataloguePort
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
import com.dynokore.gst.rules.contract.model.ImportMappingSpreadsheetRequest
import com.dynokore.gst.rules.contract.model.MapTaxCodeRequest
import com.dynokore.gst.rules.contract.model.MappingEntry
import com.dynokore.gst.rules.contract.model.MappingEntryPage
import com.dynokore.gst.rules.contract.model.MappingImportRecord
import com.dynokore.gst.rules.contract.model.MappingImportRecordPage
import com.dynokore.gst.rules.contract.model.MappingImportRecordRejectedRowsInner
import com.dynokore.gst.rules.contract.model.MappingPack
import com.dynokore.gst.rules.contract.model.RulesDryRunStatus
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.gst.rules.domain.pack.SourceClass
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
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
@Suppress("TooManyFunctions", "LongParameterList") // one handler per operation; the wired use cases
@RestController
open class PacksController(
    private val listActive: ListActiveRegulatoryPacks,
    private val authoring: MappingPackAuthoring,
    private val packs: JooqMappingPackStore,
    private val tenants: TenantContext,
    private val transactions: TransactionRunner,
    private val engineVersion: EngineVersion,
    private val imports: MappingSpreadsheetImports,
    private val importRecords: JooqMappingImportRecordStore,
    private val catalogues: CodeCataloguePort,
    private val clock: Clock,
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

    /** A body that does not match the contract (unknown enum value, malformed JSON) is SRT-001; nothing is stored. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException): ResponseEntity<String> =
        refuse(
            checkNotNull(
                refusalOf(MappingPackResult.Refused(RefusalReason.INPUT_INVALID)),
            ).also { it.addSuppressed(e) },
        )

    /**
     * A request the bean validation of the generated model refuses is 400 SRT-001 in the API-20 envelope; when the
     * refused property is `rows` (the cap of 5,000, API-49) it is 413 and no row has been looked at (INV-AC-3484).
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException): ResponseEntity<String> {
        val overCap = e.bindingResult.fieldErrors.any { it.field == "rows" && it.code == "Size" }
        val refusal =
            if (overCap) {
                MappingSpreadsheetRules.overCap()
            } else {
                checkNotNull(refusalOf(MappingPackResult.Refused(RefusalReason.INPUT_INVALID)))
            }
        return refuse(refusal.also { it.addSuppressed(e) })
    }

    override fun draftMappingPack(
        idempotencyKey: UUID,
        draftMappingPackRequest: DraftMappingPackRequest,
    ): ResponseEntity<MappingPack> =
        transactions.inTransaction {
            val label = draftMappingPackRequest.label
            if (packs.labelTaken(label)) throw labelTaken()
            val packId = newPackId()
            // The driver error is wrapped by either translator; the test is SQLSTATE 23505 plus the key name.
            val result =
                runCatching {
                    authoring.draft(
                        DraftCommand(
                            actor(),
                            draftMappingPackRequest.entityRef.orEmpty(),
                            packId,
                            tenants.current(),
                            label,
                        ),
                    )
                }.getOrElse { throw lineageKeyOr(it) }
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

    // ---- the mapping spreadsheet round trip (INV-T-4973; INV-REQ-831, INV-REQ-833) ----

    override fun importMappingSpreadsheet(
        idempotencyKey: UUID,
        importMappingSpreadsheetRequest: ImportMappingSpreadsheetRequest,
    ): ResponseEntity<MappingPack> {
        val request = importMappingSpreadsheetRequest
        val command =
            MappingSpreadsheetRules.commandOf(
                actor(),
                request.fileHash,
                request.fileName,
                request.rows,
                request.rowCount,
                request.entityRef,
            )
        // a rejected re-import commits its record, so the refusal is thrown after the transaction, not inside it
        val (answer, refusal) =
            transactions.inTransaction {
                when (val outcome = imports.import(command)) {
                    is ImportOutcome.Created -> {
                        packAnswer(outcome.packId, withApprovals = false, withCounts = true) to null
                    }

                    is ImportOutcome.Rejected -> {
                        null to MappingSpreadsheetRules.rejection(outcome.rows)
                    }

                    ImportOutcome.Forbidden -> {
                        null to
                            checkNotNull(
                                refusalOf(
                                    MappingPackResult.Forbidden(ErrorCode.SRT_004, "the action is not permitted"),
                                ),
                            )
                    }

                    ImportOutcome.Unavailable -> {
                        null to unavailable("the IRAS catalogue is not readable")
                    }
                }
            }
        refusal?.let { throw it }
        return ResponseEntity.status(HttpStatus.CREATED).body(answer)
    }

    override fun exportMappingPack(
        packId: String,
        entityRef: String?,
    ): ResponseEntity<Resource> =
        transactions.inTransaction {
            sameEntity(packId, entityRef)
            val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
            val catalogue =
                catalogues.catalogueAt(tenants.current(), TransactionDate(today))
                    ?: throw unavailable("the IRAS catalogue is not readable")
            val entries =
                packs
                    .entryViews(packId, null, Int.MAX_VALUE, null, null)
                    .map { ExportedEntry(it.internalTaxCode, it.irasCode, it.sourceClass, it.citation) }
            val file = MappingSpreadsheetCsv.export(entries, catalogue)
            ResponseEntity
                .ok()
                .contentType(MediaType("text", "csv", Charsets.UTF_8))
                .header("Content-Disposition", "attachment; filename=\"mapping-pack-$packId.csv\"")
                .body(ByteArrayResource(file.toByteArray(Charsets.UTF_8)))
        }

    override fun listMappingImportRecords(
        fileHash: String,
        entityRef: String?,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<MappingImportRecordPage> =
        transactions.inTransaction {
            val entity =
                entityRef?.takeIf { it.isNotBlank() }
                    ?: throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "entityRef is required")
            if (limit < 1) throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "limit must be 1..500")
            val after =
                cursor?.let {
                    importRecords.find(entity, fileHash, it)
                        ?: throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "cursor is malformed")
                }
            val rows = importRecords.list(entity, fileHash, after, limit + 1)
            val page = rows.take(limit).map(::recordOf)
            ResponseEntity.ok(MappingImportRecordPage(page, if (rows.size > limit) page.last().importId else null))
        }

    private fun recordOf(row: MappingImportRecordRow) =
        MappingImportRecord(
            importId = row.importId,
            importedAt = row.importedAt,
            actorRef = row.actor,
            fileHash = row.fileHash,
            fileName = row.fileName,
            rowCount = row.rowCount,
            dryRunResult = MappingImportRecord.DryRunResult.forValue(row.dryRun.name),
            appliedCount = row.appliedCount,
            rejectedRows = row.rejectedRows.map { MappingImportRecordRejectedRowsInner(it, IMPORT_REJECTED_CODE) },
            packId = row.packId,
        )

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

    /** True when [failure] or a cause is the unique violation (SQLSTATE 23505) of the lineage key of the registry. */
    private fun lineageKeyOr(e: Throwable): Throwable =
        if (violatesLineageKey(e)) labelTaken().also { it.addSuppressed(e) } else e

    private fun violatesLineageKey(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.any {
            it is java.sql.SQLException &&
                it.sqlState == UNIQUE_VIOLATION &&
                it.message.orEmpty().contains(LINEAGE_KEY)
        }

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
            ?: throw checkNotNull(refusalOf(MappingPackResult.Refused(RefusalReason.INPUT_INVALID)))

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
            activation =
                activationOf(view.noDistinctApprover).takeIf {
                    withActivation ||
                        (withCounts && view.status.name == ARMED)
                },
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
private const val UNIQUE_VIOLATION = "23505"
private const val LINEAGE_KEY = "pack_registry_lineage_key"
