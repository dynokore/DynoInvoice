package com.dynokore.gst.controlPlane.application.audit

import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.domain.audit.AuditPurgePolicy
import com.dynokore.gst.controlPlane.domain.audit.PurgeFloor
import java.time.Clock
import java.time.Instant
import java.time.Period

data class PurgeAuditEntriesCommand(
    val actor: String,
    val cutOff: Instant,
    val reason: String?,
    val context: RequestContext,
)

sealed interface PurgeAuditOutcome {
    data class Purged(
        val result: AuditPurgeResult,
    ) : PurgeAuditOutcome

    /** SRT-004 (403). */
    data object NotPermitted : PurgeAuditOutcome

    /** CPL-001 (422). */
    data object CutOffYoungerThanFloor : PurgeAuditOutcome

    /** CPL-002 (503, retryable, nothing deleted). */
    data object FloorUndeterminable : PurgeAuditOutcome
}

/**
 * Purges audit entries at or before a cut-off. Order: the authority (deny by default on refusal, error or timeout),
 * the floor, the cut-off check, the purger.
 */
class PurgeAuditEntries(
    private val authority: AuditPurgeAuthority,
    private val floorSource: AuditPurgeFloorSource,
    private val purger: AuditEntryPurger,
    private val clock: Clock,
) {
    fun execute(command: PurgeAuditEntriesCommand): PurgeAuditOutcome =
        if (!permitted(command.actor)) {
            PurgeAuditOutcome.NotPermitted
        } else {
            when (val floor = AuditPurgePolicy.floor(figures(), clock.instant())) {
                PurgeFloor.Undeterminable -> {
                    PurgeAuditOutcome.FloorUndeterminable
                }

                is PurgeFloor.Determined -> {
                    if (AuditPurgePolicy.allows(command.cutOff, floor)) {
                        PurgeAuditOutcome.Purged(
                            purger.purge(command.actor, command.cutOff, reasonOf(command), command.context),
                        )
                    } else {
                        PurgeAuditOutcome.CutOffYoungerThanFloor
                    }
                }
            }
        }

    /** The caller's text, otherwise the Idempotency-Key (INV-AC-3823). */
    private fun reasonOf(command: PurgeAuditEntriesCommand): String =
        command.reason?.takeUnless { it.isBlank() } ?: command.context.causationId

    private fun permitted(actor: String): Boolean =
        try {
            authority.allows(actor, AuditPurgePermission.KEY, AuditPurgePermission.SCOPE)
        } catch (_: Exception) {
            false
        }

    private fun figures(): List<Period?> =
        try {
            floorSource.regionRetentions()
        } catch (_: Exception) {
            emptyList()
        }
}
