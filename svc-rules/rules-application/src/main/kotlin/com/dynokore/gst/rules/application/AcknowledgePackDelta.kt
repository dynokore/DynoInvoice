package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.domain.pack.GraceEnd
import com.dynokore.gst.rules.domain.pack.GraceWindow
import com.dynokore.gst.rules.domain.pack.PackStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** The answer of a tenant's acknowledgement of its own delta; every refusal writes nothing. */
sealed interface AcknowledgePackDeltaResult {
    /** The contract answer: status ACKNOWLEDGED, the staged `effectiveFrom`, and the tenant's window end. */
    data class Acknowledged(
        val status: PackStatus,
        val effectiveFrom: LocalDate,
        val graceWindowEnd: LocalDate,
    ) : AcknowledgePackDeltaResult

    data object Denied : AcknowledgePackDeltaResult

    data object NotFound : AcknowledgePackDeltaResult

    /** The pack is neither STAGED nor APPROVED. */
    data class NotOpen(
        val status: PackStatus,
    ) : AcknowledgePackDeltaResult

    data object NoCompleteDelta : AcknowledgePackDeltaResult

    /** The private form has no grace window, so the contract precondition "grace window open" does not hold. */
    data object NoGraceWindow : AcknowledgePackDeltaResult

    /** The tenant's grace window ends today or earlier. */
    data class WindowClosed(
        val endsOn: LocalDate,
    ) : AcknowledgePackDeltaResult
}

/** The calling tenant acknowledges its own delta (INV-REQ-858, 952); the registry status does not change. */
class AcknowledgePackDelta(
    private val permission: AcknowledgementPermissionPort,
    private val registry: RegistryRowReader,
    private val deltas: CompleteDeltaReader,
    private val policies: TenantReviewPolicyReader,
    private val store: DeltaAcknowledgementStore,
    private val clock: Clock,
) {
    fun execute(
        tenantId: String,
        packType: String,
        version: String,
        actor: String,
    ): AcknowledgePackDeltaResult {
        val allowed = permission.mayAcknowledge(actor)
        val row = if (allowed) registry.find(packType, version) else null
        val completedAt = if (row != null) deltas.completedAt(tenantId, packType, version) else null
        val open = row?.status == PackStatus.STAGED || row?.status == PackStatus.APPROVED
        return when {
            !allowed -> AcknowledgePackDeltaResult.Denied
            row == null -> AcknowledgePackDeltaResult.NotFound
            !open -> AcknowledgePackDeltaResult.NotOpen(row.status)
            completedAt == null -> AcknowledgePackDeltaResult.NoCompleteDelta
            else -> acknowledge(tenantId, packType, version, actor, row, completedAt)
        }
    }

    @Suppress("LongParameterList") // one private step of the use case; every input is a distinct fact
    private fun acknowledge(
        tenantId: String,
        packType: String,
        version: String,
        actor: String,
        row: RegistryPackRow,
        completedAt: Instant,
    ): AcknowledgePackDeltaResult {
        val p = policies.read(tenantId)
        val end =
            GraceWindow.endsOn(
                p.form,
                completedAt.atZone(clock.zone).toLocalDate(),
                row.effectiveFrom,
                p.stored,
                p.maximum,
            )
        val ends = (end as? GraceEnd.Ends)?.on
        val closed = ends != null && !ends.isAfter(LocalDate.now(clock))
        return when {
            ends == null -> {
                AcknowledgePackDeltaResult.NoGraceWindow
            }

            closed -> {
                AcknowledgePackDeltaResult.WindowClosed(ends)
            }

            else -> {
                store.recordOnce(DeltaAcknowledgement(tenantId, packType, version, actor, clock.instant()))
                AcknowledgePackDeltaResult.Acknowledged(PackStatus.ACKNOWLEDGED, row.effectiveFrom, ends)
            }
        }
    }
}
