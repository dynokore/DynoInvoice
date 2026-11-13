package com.dynokore.gst.masterdata.application.imports

import com.dynokore.gst.masterdata.application.counterparty.CounterpartyRepository
import com.dynokore.gst.masterdata.application.counterparty.MasterDataResolvedPublisher
import com.dynokore.gst.masterdata.application.held.HeldDocuments
import com.dynokore.gst.masterdata.application.gapreport.LongOutcome
import com.dynokore.gst.masterdata.application.gapreport.LongRunner
import com.dynokore.gst.masterdata.application.gapreport.RecordState
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyChange
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyVersion
import com.dynokore.gst.masterdata.domain.counterparty.HeldCounterparties
import com.dynokore.gst.masterdata.domain.counterparty.IdentifierInput
import com.dynokore.gst.masterdata.domain.counterparty.ImportAnalysis
import com.dynokore.gst.masterdata.domain.counterparty.ImportRow
import com.dynokore.gst.masterdata.domain.counterparty.ImportTarget
import com.dynokore.gst.masterdata.domain.counterparty.NA
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.counterparty.PostalAddress
import com.dynokore.gst.masterdata.domain.counterparty.RejectedImportRow
import com.dynokore.gst.masterdata.domain.counterparty.WriteOrigin
import com.dynokore.gst.masterdata.domain.counterparty.after
import com.dynokore.gst.masterdata.domain.counterparty.analyseImport
import com.dynokore.gst.masterdata.domain.counterparty.uenOrNull
import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** The stored dry run of an import file (INV-Q-9793): its [rows] are the ones an apply writes. */
data class DryRunRecord(
    val dryRunId: String,
    val actor: String,
    val fileHash: String,
    val rowCount: Int,
    val rows: List<ImportRow>,
    val state: RecordState,
    val startedAt: Instant,
    val completedAt: Instant?,
    val result: ImportAnalysis?,
    val error: String?,
)

/** The declared provenance of an import (`ImportCounterpartiesRequest.providedBy`). */
enum class ImportProvenance(
    val origin: WriteOrigin,
) {
    MIGRATED(WriteOrigin.IMPORT),
    INTERNAL(WriteOrigin.OPERATOR),
}

/** The FR-AG.57 record of one import; [recordHash] is the SHA-256 of [ImportRecordBytes] once SUCCEEDED. */
data class ImportRecordData(
    val importId: String,
    val entity: String,
    val actor: String,
    val dryRunId: String,
    val fileHash: String,
    val providedBy: ImportProvenance,
    val state: RecordState,
    val startedAt: Instant,
    val completedAt: Instant?,
    val appliedCount: Int?,
    val failedCount: Int?,
    val rejectedRows: List<RejectedImportRow>?,
    val recordHash: String?,
    val error: String?,
)

/** The dry run records of the bound tenant; every call runs inside the caller's transaction. */
interface DryRunStore {
    fun insertRunning(
        dryRunId: String,
        actor: String,
        fileHash: String,
        rows: List<ImportRow>,
    ): DryRunRecord

    fun finish(
        dryRunId: String,
        analysis: ImportAnalysis,
    )

    fun fail(dryRunId: String)

    fun find(dryRunId: String): DryRunRecord?

    /** Finalises FAILED every RUNNING record started before [before]; returns how many. */
    fun failRunningBefore(before: Instant): Int
}

/** The import records of the bound tenant; every call runs inside the caller's transaction. */
interface ImportStore {
    /** Inserts the RUNNING record; null when a record of [dryRunId] exists already (one record per dry run). */
    @Suppress("LongParameterList") // the columns of the record
    fun insertRunning(
        importId: String,
        entity: String,
        actor: String,
        dryRunId: String,
        fileHash: String,
        providedBy: ImportProvenance,
    ): ImportRecordData?

    @Suppress("LongParameterList") // the columns of the finalised record
    fun finish(
        importId: String,
        completedAt: Instant,
        appliedCount: Int,
        failedCount: Int,
        rejectedRows: List<RejectedImportRow>,
        recordHash: String,
    )

    fun fail(importId: String)

    fun find(importId: String): ImportRecordData?

    fun findByDryRun(dryRunId: String): ImportRecordData?

    fun failRunningBefore(before: Instant): Int
}

/** Publishes `EvidenceArtefactRecorded` (type IMPORT) in the transaction that finalises the record (INV-REQ-833). */
fun interface ImportEvidence {
    fun recorded(
        entity: String,
        importId: String,
        hash: String,
    )
}

/**
 * The bytes of a finalised import record that the evidence hash covers: one `name=value` line per field, in this
 * order, UTF-8, lines ended by a line feed; `rejectedRows` as `row|code|field` items joined by `;`.
 */
object ImportRecordBytes {
    fun of(record: ImportRecordData): ByteArray =
        listOf(
            "importId" to record.importId,
            "actorRef" to record.actor,
            "dryRunId" to record.dryRunId,
            "fileHash" to record.fileHash,
            "providedBy" to record.providedBy.name,
            "startedAt" to record.startedAt.toString(),
            "completedAt" to record.completedAt?.toString().orEmpty(),
            "appliedCount" to record.appliedCount.toString(),
            "failedCount" to record.failedCount.toString(),
            "rejectedRows" to
                record.rejectedRows.orEmpty().joinToString(";") { "${it.row}|${it.code}|${it.field.orEmpty()}" },
        ).joinToString("") { (name, value) -> "$name=$value\n" }.toByteArray(Charsets.UTF_8)

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
}

/** The held records as the analysis reads them: one consistent snapshot of the current versions. */
class HeldSnapshot(
    versions: List<CounterpartyVersion>,
) : HeldCounterparties {
    private val byRef = versions.associateBy { it.partyRef }
    private val byUen =
        versions
            .mapNotNull { v -> v.content.uenOrNull()?.let { it to v } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, held) -> held.minBy { it.partyRef.value } }

    override fun byUen(uen: String): CounterpartyVersion? = byUen[uen]

    override fun current(party: PartyRef): CounterpartyVersion? = byRef[party]
}

/** One dry run's answer: the finished [record] or the still RUNNING one (the caller answers 200 or 202). */
data class DryRunRun(
    val record: DryRunRecord,
    val finished: Boolean,
)

/**
 * `dryRunCounterpartyImport` (INV-Q-9793, INV-Q-17291): stores the rows with a RUNNING record committed before the
 * work, analyses them on the bounded executor and finalises the record. Writes no counterparty.
 */
class DryRunCounterpartyImport(
    private val tx: TransactionRunner,
    private val parties: CounterpartyRepository,
    private val store: DryRunStore,
    private val runner: LongRunner,
) {
    fun handle(
        actor: String,
        fileHash: String,
        rows: List<ImportRow>,
    ): DryRunRun {
        val id = "DRY-" + UUID.randomUUID()
        val running = tx.inTransaction { store.insertRunning(id, actor, fileHash, rows) }
        val outcome =
            runner.run {
                try {
                    tx.inTransaction { store.finish(id, analyseImport(rows, HeldSnapshot(parties.allCurrent()))) }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    tx.inTransaction { store.fail(id) }
                    throw e
                }
            }
        return when (outcome) {
            is LongOutcome.StillRunning -> DryRunRun(running, false)
            is LongOutcome.Finished -> DryRunRun(checkNotNull(tx.inTransaction { store.find(id) }), true)
        }
    }
}

/** `getCounterpartyImportDryRun`: the stored dry run of the id; null when unknown. */
class ReadDryRun(
    private val tx: TransactionRunner,
    private val store: DryRunStore,
) {
    fun handle(dryRunId: String): DryRunRecord? = tx.inTransaction { store.find(dryRunId) }
}

/** The reviewed dry run cannot be applied: none named, unknown, not SUCCEEDED, or for another file (MDM-009). */
class ImportDryRunRefused : RuntimeException("the dry run cannot be applied", null, false, false)

data class ImportCommand(
    val entity: String,
    val actor: String,
    val dryRunId: String?,
    val fileHash: String,
    val providedBy: ImportProvenance,
)

/** One import's answer: the [record] (SUCCEEDED, FAILED or still RUNNING). */
data class ImportRun(
    val record: ImportRecordData,
    val finished: Boolean,
)

/**
 * `importCounterparties` (INV-Q-9797, INV-Q-17289, INV-Q-17291): applies the stored rows of a reviewed dry run.
 * A repeat
 * of the same dry run answers the existing record and writes nothing. The apply, the `MasterDataResolved` outbox rows,
 * the finalised record and the evidence publication are written in one transaction; a failed apply leaves no master
 * data and the record FAILED.
 */
@Suppress("LongParameterList") // the ports of one use case
class ImportCounterparties(
    private val tx: TransactionRunner,
    private val parties: CounterpartyRepository,
    private val dryRuns: DryRunStore,
    private val imports: ImportStore,
    private val clock: Clock,
    private val publisher: MasterDataResolvedPublisher,
    private val heldDocuments: HeldDocuments,
    private val evidence: ImportEvidence,
    private val runner: LongRunner,
) {
    fun handle(command: ImportCommand): ImportRun {
        val started = tx.inTransaction { start(command) }
        if (!started.mine) return ImportRun(started.record, started.record.state != RecordState.RUNNING)
        val importId = started.record.importId
        val outcome =
            runner.run {
                try {
                    tx.inTransaction { apply(started.record, started.dryRun) }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    tx.inTransaction { imports.fail(importId) }
                    throw e
                }
            }
        return when (outcome) {
            is LongOutcome.StillRunning -> ImportRun(started.record, false)
            is LongOutcome.Finished -> ImportRun(checkNotNull(tx.inTransaction { imports.find(importId) }), true)
        }
    }

    /** Checks the reviewed dry run, then finds the record of it or inserts the RUNNING one (one record per run). */
    private fun start(command: ImportCommand): Started {
        val dry = command.dryRunId?.let { dryRuns.find(it) }
        if (dry == null || dry.state != RecordState.SUCCEEDED || dry.fileHash != command.fileHash) {
            throw ImportDryRunRefused()
        }
        imports.findByDryRun(dry.dryRunId)?.let { return Started(it, dry, false) }
        val inserted =
            imports.insertRunning(
                "IMP-" + UUID.randomUUID(),
                command.entity,
                command.actor,
                dry.dryRunId,
                command.fileHash,
                command.providedBy,
            )
        return if (inserted == null) {
            Started(checkNotNull(imports.findByDryRun(dry.dryRunId)), dry, false)
        } else {
            Started(inserted, dry, true)
        }
    }

    private data class Started(
        val record: ImportRecordData,
        val dryRun: DryRunRecord,
        val mine: Boolean,
    )

    private fun apply(
        record: ImportRecordData,
        dry: DryRunRecord,
    ) {
        val now = clock.instant()
        val analysis = analyseImport(dry.rows, HeldSnapshot(parties.allCurrent()))
        analysis.targets.forEach { write(record, it, now) }
        val duplicateRows = analysis.duplicates.sumOf { it.rows.size }
        val done =
            record.copy(
                state = RecordState.SUCCEEDED,
                completedAt = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                appliedCount = analysis.targets.size,
                failedCount = analysis.rejected.size + duplicateRows,
                rejectedRows = analysis.rejected,
            )
        val hash = ImportRecordBytes.sha256(ImportRecordBytes.of(done))
        imports.finish(
            record.importId,
            checkNotNull(done.completedAt),
            checkNotNull(done.appliedCount),
            checkNotNull(done.failedCount),
            analysis.rejected,
            hash,
        )
        evidence.recorded(record.entity, record.importId, hash)
    }

    private fun write(
        record: ImportRecordData,
        target: ImportTarget,
        now: Instant,
    ) {
        val held = parties.current(target.party)?.content
        val data = target.data
        val uen = data.uen.orEmpty()
        val scheme = if (uen == NA) IdentifierScheme.NONE else IdentifierScheme.UEN
        val change =
            CounterpartyChange(
                identifiers = listOf(IdentifierInput(scheme, uen)),
                legalName = data.legalName,
                address = PostalAddress(data.addressLine1, held?.address?.line2, data.postalCode),
                arcsGstChargeAgreed = null,
                approvedSchemes = null,
            )
        val content = held.after(change, record.importId, record.providedBy.origin, now)
        val version = parties.append(target.party, content, now, record.importId)
        publisher.publish(record.entity, version, heldDocuments.heldFor(record.entity, target.party))
    }
}

/** `getImportRecord`: the record of the id when the calling principal started the import; null otherwise. */
class ReadImportRecord(
    private val tx: TransactionRunner,
    private val store: ImportStore,
) {
    fun handle(
        importId: String,
        actor: String,
    ): ImportRecordData? = tx.inTransaction { store.find(importId) }?.takeIf { it.actor == actor }
}
