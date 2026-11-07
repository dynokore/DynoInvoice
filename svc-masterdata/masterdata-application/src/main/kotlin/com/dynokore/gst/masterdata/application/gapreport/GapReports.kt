package com.dynokore.gst.masterdata.application.gapreport

import com.dynokore.gst.masterdata.application.counterparty.CounterpartyRepository
import com.dynokore.gst.masterdata.application.registry.LookupGstRegister
import com.dynokore.gst.masterdata.application.registry.RegisterQuestion
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyVersion
import com.dynokore.gst.masterdata.domain.counterparty.GapClass
import com.dynokore.gst.masterdata.domain.counterparty.NA
import com.dynokore.gst.masterdata.domain.counterparty.UenFormat
import com.dynokore.gst.masterdata.domain.counterparty.conflictsWithRegister
import com.dynokore.gst.masterdata.domain.counterparty.gapRows
import com.dynokore.gst.masterdata.domain.counterparty.gstnOrNull
import com.dynokore.gst.masterdata.domain.counterparty.uenOrNull
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Instant
import java.util.UUID

/** The state of a long-command record (API-17). */
enum class RecordState { RUNNING, SUCCEEDED, FAILED }

/** One listed counterparty: its class and the catalogue code of that class (null for DUPLICATE_UEN). */
data class GapFinding(
    val counterpartyRef: String,
    val findingClass: GapClass,
    val errorCode: String?,
)

/** The result of a report: [rows] and the counterparties whose register lookup was unavailable. */
data class GapReportResult(
    val rows: List<GapFinding>,
    val notChecked: List<String>,
)

/** The stored record of one gap report; [error] is the API-20 envelope text of a FAILED run. */
data class GapReportRecord(
    val gapReportId: String,
    val entity: String,
    val actor: String,
    val state: RecordState,
    val startedAt: Instant,
    val completedAt: Instant?,
    val result: GapReportResult?,
    val error: String?,
)

/**
 * The gap report records of the bound tenant; every call runs inside the caller's transaction. The store stamps the
 * times and renders the API-20 envelope of a FAILED record itself.
 */
interface GapReportStore {
    fun insertRunning(
        gapReportId: String,
        entity: String,
        actor: String,
    ): GapReportRecord

    fun finish(
        gapReportId: String,
        result: GapReportResult,
    )

    fun fail(gapReportId: String)

    fun find(gapReportId: String): GapReportRecord?
}

/** One run's answer: the finished [record] or the still RUNNING one (the caller answers 200 or 202). */
data class GapReportRun(
    val record: GapReportRecord,
    val finished: Boolean,
)

/**
 * INV-REQ-316, INV-AC-1608, INV-AC-1609, INV-AC-9599: lists the counterparties that would block or misstate
 * submission, in the five classes, and writes no master data. The RUNNING record is committed before the work starts;
 * the work runs on the bounded executor (INV-Q-17291). Class 3 goes only through [LookupGstRegister.check], the
 * register
 * use case with its cache, for the entity of the request; a counterparty whose lookup is unavailable is `notChecked`.
 */
class RunGapReport(
    private val tx: TransactionRunner,
    private val parties: CounterpartyRepository,
    private val store: GapReportStore,
    private val register: LookupGstRegister,
    private val runner: LongRunner,
) {
    fun handle(
        entity: String,
        actor: String,
    ): GapReportRun {
        val id = "GAP-" + UUID.randomUUID()
        val running = tx.inTransaction { store.insertRunning(id, entity, actor) }
        val outcome =
            runner.run {
                try {
                    val result = compute(entity)
                    tx.inTransaction { store.finish(id, result) }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    tx.inTransaction { store.fail(id) }
                    throw e
                }
            }
        return when (outcome) {
            is LongOutcome.StillRunning -> GapReportRun(running, false)
            is LongOutcome.Finished -> GapReportRun(checkNotNull(tx.inTransaction { store.find(id) }), true)
        }
    }

    private fun compute(entity: String): GapReportResult {
        val current = tx.inTransaction { parties.allCurrent() }
        val rows =
            gapRows(current) { UenFormat.accepts(it) }
                .map { GapFinding(it.party.value, it.gapClass, it.gapClass.errorCode) }
                .toMutableList()
        val notChecked = mutableListOf<String>()
        current.forEach { version -> registerFinding(entity, version, rows, notChecked) }
        return GapReportResult(
            rows.sortedWith(compareBy({ it.counterpartyRef }, { it.findingClass.ordinal })),
            notChecked.sorted(),
        )
    }

    private fun registerFinding(
        entity: String,
        version: CounterpartyVersion,
        rows: MutableList<GapFinding>,
        notChecked: MutableList<String>,
    ) {
        val uen = version.content.uenOrNull() ?: return
        val gstn = version.content.gstnOrNull()
        if (uen == NA || gstn == null || !UenFormat.accepts(uen)) return
        val fact = register.check(RegisterQuestion(entity, uen, version.partyRef))
        if (fact == null) {
            notChecked += version.partyRef.value
        } else if (conflictsWithRegister(gstn, fact.found, fact.status, fact.gstNumber)) {
            rows +=
                GapFinding(
                    version.partyRef.value,
                    GapClass.GST_REGISTER_CONFLICT,
                    GapClass.GST_REGISTER_CONFLICT.errorCode,
                )
        }
    }
}

/** `getGapReport`: the record of the given id when the calling principal started it; null otherwise (SRT-005). */
class ReadGapReport(
    private val tx: TransactionRunner,
    private val store: GapReportStore,
) {
    fun handle(
        gapReportId: String,
        actor: String,
    ): GapReportRecord? = tx.inTransaction { store.find(gapReportId) }?.takeIf { it.actor == actor }
}

/** One export line: the counterparty's current values; [findingClass] and [errorCode] are null for notChecked. */
data class ExportLine(
    val counterpartyRef: String,
    val findingClass: GapClass?,
    val errorCode: String?,
    val uen: String?,
    val legalName: String?,
    val addressLine1: String?,
    val postalCode: String?,
)

/** The audit entry of one export, written in the export's transaction (INV-REQ-832): the acting user, never a role. */
fun interface ExportAudit {
    fun exported(
        actor: String,
        entity: String,
        gapReportId: String,
    )
}

/**
 * `exportGapReport` (INV-Q-9795): the lines of a SUCCEEDED report of the calling principal, one per row and one per
 * notChecked entry, with the counterparty's current values; null for an unknown, foreign, RUNNING or FAILED report.
 * One audit entry per export. Reads no master data it changes.
 */
class ExportGapReport(
    private val tx: TransactionRunner,
    private val parties: CounterpartyRepository,
    private val store: GapReportStore,
    private val audit: ExportAudit,
) {
    fun handle(
        gapReportId: String,
        actor: String,
    ): List<ExportLine>? =
        tx.inTransaction {
            val record = store.find(gapReportId)?.takeIf { it.actor == actor && it.state == RecordState.SUCCEEDED }
            val result = record?.result ?: return@inTransaction null

            fun line(
                ref: String,
                gapClass: GapClass?,
                code: String?,
            ): ExportLine {
                val content =
                    parties
                        .current(
                            com.dynokore.gst.masterdata.domain.counterparty
                                .PartyRef(ref),
                        )?.content
                return ExportLine(
                    ref,
                    gapClass,
                    code,
                    content?.uenOrNull(),
                    content?.legalName,
                    content?.address?.line1,
                    content?.address?.postalCode,
                )
            }
            val lines =
                result.rows.map { line(it.counterpartyRef, it.findingClass, it.errorCode) } +
                    result.notChecked.map { line(it, null, null) }
            audit.exported(actor, record.entity, gapReportId)
            lines
        }
}
