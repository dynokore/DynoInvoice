package com.dynokore.gst.access.application.breakglass

import com.dynokore.gst.access.domain.breakglass.BreakGlassAction
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Instant

/** Actions reported by another service as an event, held once per pair of the bound tenant and report id. */
interface BreakGlassReportedActions {
    /** The action reference stored for [reportId] in the bound tenant, or null. */
    fun find(reportId: String): String?

    /** Stores the action and returns its new reference, or null when the tenant and [reportId] pair is held. */
    fun store(
        unlockRef: String,
        action: BreakGlassAction,
        target: String?,
        callerService: String,
        reportId: String,
    ): String?
}

/** Why a reported action was not stored. */
enum class ReportRefusal { NOT_OPEN, NOT_FOUND, BAD_ACTION }

/** Told once of every refused report, so the caller can count and log it. */
fun interface BreakGlassReportObserver {
    fun refused(
        reportId: String,
        unlockRef: String,
        reason: ReportRefusal,
    )
}

sealed interface ReportBreakGlassOutcome {
    data class Stored(
        val actionRef: String,
    ) : ReportBreakGlassOutcome

    /** The report id was already held: nothing is stored or raised again. */
    data object Duplicate : ReportBreakGlassOutcome

    data class Refused(
        val reason: ReportRefusal,
    ) : ReportBreakGlassOutcome
}

/**
 * Records a break-glass action reported as an event (INV-Q-9117 A, INV-Q-9313 A) with the same ACC-002 alert and audit
 * entry as the synchronous `recordBreakGlassAction`. The duplicate check comes before the session check, so a
 * redelivery stays a duplicate after the session was resealed. The session is judged at [occurredAt], never now.
 */
@Suppress("UnusedPrivateProperty") // the clock is part of the declared signature; the session is judged at occurredAt
class ReportBreakGlassActionUseCase(
    private val tx: TransactionRunner,
    private val sessions: BreakGlassSessions,
    private val reported: BreakGlassReportedActions,
    private val effects: BreakGlassEffects,
    private val observer: BreakGlassReportObserver,
    private val clock: Clock,
) {
    @Suppress("LongParameterList")
    fun handle(
        actor: String,
        reportId: String,
        unlockRef: String,
        action: String,
        target: String?,
        occurredAt: Instant,
        callerService: String,
    ): ReportBreakGlassOutcome =
        tx.inTransaction {
            val named =
                BreakGlassAction.entries.firstOrNull { it.name == action }
                    ?: return@inTransaction refuse(reportId, unlockRef, ReportRefusal.BAD_ACTION)
            if (reported.find(reportId) != null) return@inTransaction ReportBreakGlassOutcome.Duplicate
            val session =
                sessions.find(unlockRef)
                    ?: return@inTransaction refuse(reportId, unlockRef, ReportRefusal.NOT_FOUND)
            if (!session.isOpenAt(occurredAt)) {
                return@inTransaction refuse(reportId, unlockRef, ReportRefusal.NOT_OPEN)
            }
            val ref =
                reported.store(unlockRef, named, target, callerService, reportId)
                    ?: return@inTransaction ReportBreakGlassOutcome.Duplicate
            effects.record(actor, "recordBreakGlassAction", unlockRef, named.name, "${named.name} by $callerService")
            ReportBreakGlassOutcome.Stored(ref)
        }

    private fun refuse(
        reportId: String,
        unlockRef: String,
        reason: ReportRefusal,
    ): ReportBreakGlassOutcome {
        observer.refused(reportId, unlockRef, reason)
        return ReportBreakGlassOutcome.Refused(reason)
    }
}
