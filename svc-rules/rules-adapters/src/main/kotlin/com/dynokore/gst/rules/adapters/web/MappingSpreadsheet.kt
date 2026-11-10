package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.adapters.persistence.ImportDryRun
import com.dynokore.gst.rules.adapters.persistence.ImportedEntry
import com.dynokore.gst.rules.adapters.persistence.JooqMappingImportRecordStore
import com.dynokore.gst.rules.adapters.persistence.JooqMappingPackStore
import com.dynokore.gst.rules.adapters.persistence.MappingImportRecordRow
import com.dynokore.gst.rules.application.CodeCataloguePort
import com.dynokore.gst.rules.application.MappingPackDraft
import com.dynokore.gst.rules.application.MappingPackPermissions
import com.dynokore.gst.rules.application.PackActionAuthority
import com.dynokore.gst.rules.contract.model.MappingSpreadsheetRow
import com.dynokore.gst.rules.domain.coding.CodeCatalogue
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.gst.rules.domain.pack.SourceClass
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.events.EvidenceArtefact
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactRecorder
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactType
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/** The most rows one re-import holds (INV-REQ-831 b5, API-49, INV-AC-3484). */
const val MAX_IMPORT_ROWS = 5_000

/** One mapped row of a re-import as the use case reads it (description and rate are dropped on import). */
data class ImportRow(
    val internalTaxCode: String,
    val irasCode: String,
    val sourceClass: SourceClass,
    val citation: String?,
)

/** A re-import call as the use case receives it; [actor] is the user ID of the token principal, never the request's. */
data class ImportCommand(
    val actor: String,
    val entityRef: String,
    val fileHash: String,
    val fileName: String,
    val rowCount: Int,
    val rows: List<ImportRow>,
)

/** What a re-import came to. [Rejected] is committed with its record; every other refusal stores nothing. */
sealed interface ImportOutcome {
    data class Created(
        val packId: String,
    ) : ImportOutcome

    data class Rejected(
        val importId: String,
        val rows: List<Int>,
    ) : ImportOutcome

    data object Forbidden : ImportOutcome

    data object Unavailable : ImportOutcome
}

/** The re-import of a mapping spreadsheet as a new draft; a seam so that a test counts the calls it receives. */
fun interface MappingSpreadsheetImports {
    fun import(command: ImportCommand): ImportOutcome
}

/**
 * The re-import (INV-REQ-831, INV-REQ-833): permission by the scoped key, the IRAS catalogue check of every row, then
 * either the draft with its entries and the clean record, or the record alone with the rejected rows (RUL-006, no
 * draft). The record is published as `EvidenceArtefactRecorded` (type `IMPORT`) in the same transaction. Runs inside
 * the transaction of the caller; a repeat of a clean file returns its draft and stores nothing (contract repeatKey).
 */
@Suppress("LongParameterList") // the seven collaborators of the use case, wired once in the composition root
class MappingSpreadsheetImporter(
    private val packs: JooqMappingPackStore,
    private val records: JooqMappingImportRecordStore,
    private val authority: PackActionAuthority,
    private val catalogues: CodeCataloguePort,
    private val evidence: EvidenceArtefactRecorder,
    private val tenants: TenantContext,
    private val clock: Clock,
) : MappingSpreadsheetImports {
    override fun import(command: ImportCommand): ImportOutcome =
        when {
            !authority.holds(command.actor, command.entityRef, MappingPackPermissions.AUTHOR) -> ImportOutcome.Forbidden
            else -> repeatOf(command) ?: check(command)
        }

    /** A repeat of a clean file answers its draft (contract repeatKey `fileHash`). */
    private fun repeatOf(command: ImportCommand): ImportOutcome? =
        records.cleanOf(command.entityRef, command.fileHash)?.packId?.let { ImportOutcome.Created(it) }

    private fun check(command: ImportCommand): ImportOutcome {
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        val catalogue =
            catalogues.catalogueAt(tenants.current(), TransactionDate(today)) ?: return ImportOutcome.Unavailable
        val rejected =
            command.rows
                .withIndex()
                .filter { !catalogue.contains(it.value.irasCode) }
                .map { it.index + 1 }
        val importId = "IMP-" + hex(IMPORT_ID_HEX)
        return if (rejected.isEmpty()) createDraft(command, importId) else reject(command, importId, rejected)
    }

    private fun hex(length: Int): String =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(length)
            .uppercase(Locale.ROOT)

    private fun reject(
        command: ImportCommand,
        importId: String,
        rejected: List<Int>,
    ): ImportOutcome {
        records.insert(record(command, importId, ImportDryRun.REJECTED, 0, rejected, null))
        publish(command, importId)
        return ImportOutcome.Rejected(importId, rejected)
    }

    private fun createDraft(
        command: ImportCommand,
        importId: String,
    ): ImportOutcome {
        val packId = "MAP-" + hex(PACK_ID_HEX)
        packs.draft(
            MappingPackDraft(
                packId,
                tenants.current(),
                command.entityRef,
                "import-" + importId.lowercase(Locale.ROOT),
                command.actor,
                PackStatus.DRAFT,
            ),
        )
        records.insert(record(command, importId, ImportDryRun.CLEAN, command.rows.size, emptyList(), packId))
        packs.insertImported(
            packId,
            importId,
            command.rows.map { ImportedEntry(it.internalTaxCode, it.irasCode, it.sourceClass, it.citation) },
        )
        publish(command, importId)
        return ImportOutcome.Created(packId)
    }

    private fun record(
        command: ImportCommand,
        importId: String,
        result: ImportDryRun,
        applied: Int,
        rejected: List<Int>,
        packId: String?,
    ) = MappingImportRecordRow(
        importId = importId,
        importedAt = clock.instant().atOffset(ZoneOffset.UTC),
        entityRef = command.entityRef,
        actor = command.actor,
        fileHash = command.fileHash,
        fileName = command.fileName,
        rowCount = command.rows.size,
        dryRun = result,
        appliedCount = applied,
        rejectedRows = rejected,
        packId = packId,
    )

    /** `EvidenceArtefactRecorded`, type IMPORT, reference the import id, hash the file's SHA-256 (INV-REQ-833 b3). */
    private fun publish(
        command: ImportCommand,
        importId: String,
    ) {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val causation = CorrelationContext.currentCausationId()?.value ?: correlation
        evidence.record(
            EvidenceArtefact(
                EvidenceArtefactType.IMPORT,
                importId,
                MappingSpreadsheetRules.sha256Hex(command.fileHash),
            ),
            EventContext(
                tenant = tenants.current(),
                entity = EntityRef(command.entityRef),
                docUuid = null,
                transactionDate = null,
                aggregateKey = importId,
                aggregateVersion = null,
                correlation = CorrelationId(correlation),
                causation = CausationId(causation),
            ),
        )
    }

    private companion object {
        const val IMPORT_ID_HEX = 12
        const val PACK_ID_HEX = 8
    }
}

/** The request checks of the re-import and the refusals it answers; none of them touches the store. */
object MappingSpreadsheetRules {
    private const val MAX_ENTITY_REF = 64
    private const val MAX_FILE_NAME = 200
    private const val MAX_CODE = 40
    private const val MAX_CITATION = 500
    private const val UNPROCESSABLE = 422
    private const val TOO_LARGE = 413
    private val SHA256 = Regex("^(?:sha256:)?([0-9a-fA-F]{64})$")

    /** The 64 lowercase hex characters of a `fileHash` that [commandOf] accepted (the `sha256:` prefix is optional). */
    fun sha256Hex(fileHash: String): String =
        checkNotNull(SHA256.matchEntire(fileHash)).groupValues[1].lowercase(Locale.ROOT)

    private fun invalid(detail: String) =
        ClosedErrorCode.CONTRACT_VIOLATION.let { MappingRefusal(it.httpStatus, it.code, it.title, detail) }

    private fun blankOrControl(value: String) = value.isBlank() || value.any { it.isISOControl() }

    /**
     * The command of a request, or the refusal: more than 5,000 rows is 413 before anything else is looked at (the
     * rows are never validated), a missing `rows`, `entityRef` or a `rowCount` that differs from the number of rows is
     * 400 SRT-001. The actor is the bound principal's, never a request value.
     */
    @Suppress("CyclomaticComplexMethod", "ThrowsCount", "LongParameterList") // independent request checks
    fun commandOf(
        actor: String,
        fileHash: String,
        fileName: String,
        rows: List<MappingSpreadsheetRow>?,
        rowCount: Int?,
        entityRef: String?,
    ): ImportCommand {
        if (rows == null) throw invalid("rows is required")
        if (rows.size > MAX_IMPORT_ROWS) throw overCap()
        if (entityRef == null || blankOrControl(entityRef) || entityRef.length > MAX_ENTITY_REF) {
            throw invalid("entityRef is required")
        }
        if (rowCount != rows.size) throw invalid("rowCount must equal the number of rows")
        if (rows.isEmpty()) throw invalid("a re-import holds at least one row")
        if (!SHA256.matches(fileHash)) throw invalid("fileHash must be a SHA-256")
        if (blankOrControl(fileName) || fileName.length > MAX_FILE_NAME) throw invalid("fileName is invalid")
        val mapped =
            rows.mapIndexed { index, row ->
                val citation = row.citation?.takeIf { it.isNotBlank() }
                val valid =
                    !blankOrControl(row.internalTaxCode) &&
                        row.internalTaxCode.length <= MAX_CODE &&
                        !blankOrControl(row.irasCode) &&
                        (citation == null || citation.length <= MAX_CITATION)
                if (!valid) throw invalid("row ${index + 1} is invalid")
                ImportRow(
                    row.internalTaxCode,
                    row.irasCode,
                    SourceClass.valueOf(row.sourceClass.value),
                    citation,
                )
            }
        val duplicate =
            mapped
                .groupBy { it.internalTaxCode }
                .filterValues { it.size > 1 }
                .keys
                .firstOrNull()
        if (duplicate != null) throw invalid("internalTaxCode occurs twice: $duplicate")
        return ImportCommand(actor, entityRef, fileHash, fileName, rows.size, mapped)
    }

    /** 413 for a request over the 5,000-row cap, refused whole before any row is looked at (API-49, INV-AC-3484). */
    fun overCap() =
        ClosedErrorCode.REQUEST_TOO_LARGE.let {
            MappingRefusal(TOO_LARGE, it.code, it.title, "a re-import holds at most $MAX_IMPORT_ROWS rows")
        }

    /** 422 RUL-006 for a rejected re-import; `errors[]` names each rejected row (API-21, INV-AC-2039). */
    fun rejection(rows: List<Int>): MappingRefusal {
        val code = ErrorCode.RUL_006
        return MappingRefusal(
            UNPROCESSABLE,
            code.code,
            "Mapping pack refused",
            "${rows.size} row(s) carry a category code absent from the IRAS catalogue; no draft is created",
            code.severity.name,
            code.disposition?.name,
            rows.map { RefusalError(code.code, "category code absent from the IRAS catalogue", "row $it") },
        )
    }
}

/** The CSV file of `exportMappingPack` (RFC 4180, UTF-8): the header and one line per entry. */
object MappingSpreadsheetCsv {
    const val HEADER = "internalTaxCode,description,irasCode,rate,sourceClass,citation"
    private const val CRLF = "\r\n"
    private val SPECIAL = setOf(',', '"', '\r', '\n')

    /** One field, quoted (with doubled quotes) exactly when it holds a comma, a quote, a CR or an LF. */
    fun field(value: String): String =
        if (value.any { it in SPECIAL }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    /** The description is the Annex E group of the category code and the rate its Annex E rate (INV-REQ-831 b1). */
    fun export(
        entries: List<ExportedEntry>,
        catalogue: CodeCatalogue,
    ): String =
        buildString {
            append(HEADER).append(CRLF)
            entries.forEach {
                val code = catalogue.entryOf(it.irasCode)
                listOf(
                    it.internalTaxCode,
                    code?.group.orEmpty(),
                    it.irasCode,
                    code?.annexERate.orEmpty(),
                    it.sourceClass.name,
                    it.citation.orEmpty(),
                ).joinTo(this, ",") { value -> field(value) }
                append(CRLF)
            }
        }
}

/** An entry of a pack as the export reads it. */
data class ExportedEntry(
    val internalTaxCode: String,
    val irasCode: String,
    val sourceClass: SourceClass,
    val citation: String?,
)
