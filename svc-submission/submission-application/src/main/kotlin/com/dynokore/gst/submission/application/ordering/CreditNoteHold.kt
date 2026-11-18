package com.dynokore.gst.submission.application.ordering

import com.dynokore.gst.submission.application.arrival.CodeRaising
import com.dynokore.gst.submission.application.arrival.ScopeEntry
import com.dynokore.gst.submission.application.arrival.ScopeFacts
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.gst.submission.domain.ordering.CreditNoteDecision
import com.dynokore.gst.submission.domain.ordering.CreditNoteOrdering
import com.dynokore.gst.submission.domain.ordering.CreditNoteOrderingInput
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.ZoneId

/** The result of [CreditNoteHold.onArrival]: the version is held, or it proceeds in [lane] (null: the plan's lane). */
sealed interface HoldOutcome {
    data object Held : HoldOutcome

    data class Proceed(
        val lane: Lane?,
    ) : HoldOutcome
}

/**
 * The arrival hold of a first credit note version whose preceding document is not yet at IRAS (INV-REQ-709,
 * INV-AC-24521). It runs in the caller's transaction, opens none and holds no queue: a held version is
 * AWAITING_PRECEDING with SUB-011 and is not enqueued. A credit note whose deadline is unknown is never held, since a
 * hold with no known deadline could delay it silently (INV-REQ-721). An unreadable option throws (fail closed).
 */
@Suppress("LongParameterList") // the constructor shape is ruled (INV-T-24524)
class CreditNoteHold(
    private val facts: ScopeFacts,
    private val options: CreditNoteOptions,
    private val states: PrecedingStates,
    private val releaseAt: ReleaseAloneAt,
    private val store: HoldStore,
    private val raising: CodeRaising,
    private val clock: Clock,
    private val zone: ZoneId,
) {
    @Suppress("ReturnCount") // each early return is one rule of the design
    fun onArrival(
        tenant: TenantId,
        docUuid: String,
        version: Int,
        firstVersion: Boolean,
    ): HoldOutcome {
        if (!firstVersion) return PROCEED
        val entry = facts.of(tenant, docUuid)?.takeIf { it.kind == RowKind.CREDIT_NOTE } ?: return PROCEED
        val preceding = entry.precedingDocUuid ?: return PROCEED
        val releaseAloneAt = releaseAt.of(tenant, docUuid) ?: return PROCEED
        val now = clock.instant()
        val decision =
            CreditNoteOrdering.decide(
                CreditNoteOrderingInput(
                    preceding,
                    states.of(tenant, preceding),
                    options.at(tenant, entry.entityRef, now.atZone(zone).toLocalDate()),
                    now,
                    releaseAloneAt,
                ),
            )
        return when (decision) {
            is CreditNoteDecision.Release -> {
                PROCEED
            }

            is CreditNoteDecision.Hold -> {
                hold(tenant, docUuid, version)
                raise(tenant, docUuid, version, entry, CreditNoteDecision.SUB_011)
                HoldOutcome.Held
            }

            is CreditNoteDecision.ReleaseAlone -> {
                raise(tenant, docUuid, version, entry, CreditNoteDecision.SUB_012)
                HoldOutcome.Proceed(Lane.DEADLINE_NEAR)
            }

            is CreditNoteDecision.HoldPastMargin -> {
                hold(tenant, docUuid, version)
                raise(tenant, docUuid, version, entry, CreditNoteDecision.SUB_012)
                HoldOutcome.Held
            }
        }
    }

    private fun hold(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ) {
        val changed = store.holdReady(tenant, docUuid, version)
        check(changed == 1) { "hold of $docUuid version $version changed $changed rows" }
    }

    private fun raise(
        tenant: TenantId,
        docUuid: String,
        version: Int,
        entry: ScopeEntry,
        code: String,
    ) {
        raising.raise(tenant, docUuid, version, entry.entityRef, entry.periodRef, listOf(code))
    }

    private companion object {
        val PROCEED = HoldOutcome.Proceed(null)
    }
}
