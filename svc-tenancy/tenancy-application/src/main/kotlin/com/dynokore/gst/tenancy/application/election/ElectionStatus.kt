package com.dynokore.gst.tenancy.application.election

import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.gst.tenancy.domain.ElectionRules
import com.dynokore.gst.tenancy.domain.ElectionStatus
import com.dynokore.gst.tenancy.domain.FactAnswer
import com.dynokore.gst.tenancy.domain.StatusTimeline
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.LocalDate

/** One election on a date: its status and, when elected, the value in force with its date and actor. */
data class ElectionView(
    val kind: ElectionKind,
    val status: ElectionStatus,
    val value: String? = null,
    val basis: String? = null,
    val effectiveFrom: LocalDate? = null,
    val setBy: String? = null,
)

/** The four elections of an entity on [on], in the order of [ElectionKind]. */
data class ElectionStatusReport(
    val entityRef: String,
    val on: LocalDate,
    val elections: List<ElectionView>,
) {
    val outstanding: List<ElectionKind> get() =
        elections
            .filter {
                it.status == ElectionStatus.OUTSTANDING
            }.map { it.kind }
}

/** Reads the status of the four go-live elections (getElectionStatus; the contract ElectionState). */
class ElectionStatusQueries(
    private val tx: TransactionRunner,
    private val structure: StructureStore,
    private val elections: ElectionPolicyStore,
    private val facts: EntityFactSource,
) {
    /** The report, or null when the entity is not in the caller's tenant (the port is not asked for it then). */
    fun status(
        entityRef: String,
        on: LocalDate,
    ): ElectionStatusReport? =
        tx.inTransaction {
            val entity = structure.entity(entityRef) ?: return@inTransaction null
            ElectionStatusReport(entity.ref, on, ElectionKind.entries.map { view(entity.ref, it, on) })
        }

    private fun view(
        entityRef: String,
        kind: ElectionKind,
        on: LocalDate,
    ): ElectionView {
        val inForce = StatusTimeline.inForceOn(elections.history(entityRef, kind), on)
        val status = ElectionRules.status(kind, inForce != null, fact(entityRef, kind, inForce != null))
        return ElectionView(kind, status, inForce?.value, inForce?.basis, inForce?.effectiveFrom, inForce?.setBy)
    }

    /** The fact is read only for an election not yet made that a fact can exempt. */
    private fun fact(
        entityRef: String,
        kind: ElectionKind,
        elected: Boolean,
    ): FactAnswer =
        when {
            elected -> FactAnswer.NOT_RECORDED
            kind == ElectionKind.APPORTIONMENT_MODE -> facts.recordsPurchases(entityRef)
            kind == ElectionKind.EXCHANGE_RATE_SOURCE -> facts.hasForeignCurrencyDocuments(entityRef)
            else -> FactAnswer.NOT_RECORDED
        }
}

/** The result of the go-live gate: allowed exactly when no election is outstanding. */
sealed interface ElectionGateDecision {
    data class Allowed(
        val report: ElectionStatusReport,
    ) : ElectionGateDecision

    /** go-live is refused naming the [outstanding] elections (INV-AC-2272). */
    data class Refused(
        val report: ElectionStatusReport,
        val outstanding: List<ElectionKind>,
    ) : ElectionGateDecision

    data object EntityNotFound : ElectionGateDecision
}

/** The election gate goLive of INV-T-4931 calls (INV-REQ-987, INV-REQ-422, INV-REQ-1286). */
class ElectionGate(
    private val queries: ElectionStatusQueries,
) {
    fun check(
        entityRef: String,
        on: LocalDate,
    ): ElectionGateDecision {
        val report = queries.status(entityRef, on) ?: return ElectionGateDecision.EntityNotFound
        return report.outstanding.takeIf { it.isNotEmpty() }?.let { ElectionGateDecision.Refused(report, it) }
            ?: ElectionGateDecision.Allowed(report)
    }
}
