package com.dynokore.gst.submission.application.ordering

import com.dynokore.gst.submission.application.arrival.CodeRaising
import com.dynokore.gst.submission.application.queue.SubmissionQueue
import com.dynokore.gst.submission.application.queue.TenantTransactions
import com.dynokore.gst.submission.domain.ordering.CreditNoteDecision
import com.dynokore.gst.submission.domain.ordering.CreditNoteOrdering
import com.dynokore.gst.submission.domain.ordering.CreditNoteOrderingInput
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The deadline and safety sweep of the held credit notes (INV-REQ-709, INV-REQ-721): the safety net of the release at
 * acknowledgement ([HeldRelease]) and the only place where the release-alone instant takes effect for a version that
 * is already held. Per tenant it takes up to [BATCH] held versions and decides each in its own transaction with
 * [CreditNoteOrdering.decide]: a release goes to lane LIVE, a release alone (SUB-012) to lane DEADLINE_NEAR, a hold
 * past the margin only raises SUB-012 (the approver release command is not built) and a hold does nothing. The sweep
 * is idempotent (the SUB-012 append and the guarded `releaseHeld` are); a failure of one version is logged, rolls
 * back that version's transaction and the batch goes on.
 */
@Suppress("LongParameterList") // composition root of ten ports, as ruled in the task
class HeldCreditNoteSweeper(
    private val source: HeldTenantSource,
    private val scope: TenantTransactions,
    private val store: HoldStore,
    private val states: PrecedingStates,
    private val options: CreditNoteOptions,
    private val releaseAt: ReleaseAloneAt,
    private val queue: SubmissionQueue,
    private val raising: CodeRaising,
    private val clock: Clock,
    private val zone: ZoneId,
) {
    /** @return the number of versions released. */
    fun sweep(): Int =
        source.tenants().sumOf { tenant ->
            scope.bound(tenant) {
                val held = scope.inTransaction { store.held(tenant, BATCH) }
                held.count { version -> sweepOne(tenant, version) }
            }
        }

    private fun sweepOne(
        tenant: TenantId,
        version: HeldVersion,
    ): Boolean =
        try {
            scope.inTransaction { decide(tenant, version) }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            LOG.log(
                System.Logger.Level.ERROR,
                "held credit note sweep: ${version.docUuid} v${version.version} failed",
                e,
            )
            false
        }

    private fun decide(
        tenant: TenantId,
        held: HeldVersion,
    ): Boolean {
        val preceding = states.of(tenant, held.precedingDocUuid)
        val now = clock.instant()
        val option = options.at(tenant, held.entityRef, now.atZone(zone).toLocalDate())
        val deadline = releaseAt.of(tenant, held.docUuid) ?: unknownDeadline(held)
        val input = CreditNoteOrderingInput(held.precedingDocUuid, preceding, option, now, deadline)
        return when (val decision = CreditNoteOrdering.decide(input)) {
            is CreditNoteDecision.Release -> {
                release(tenant, held, Lane.LIVE)
            }

            is CreditNoteDecision.ReleaseAlone -> {
                raise(tenant, held, decision.code)
                release(tenant, held, Lane.DEADLINE_NEAR)
            }

            is CreditNoteDecision.HoldPastMargin -> {
                raise(tenant, held, decision.code)
                false
            }

            is CreditNoteDecision.Hold -> {
                false
            }
        }
    }

    private fun unknownDeadline(held: HeldVersion): Instant {
        LOG.log(System.Logger.Level.WARNING, "release-alone instant of ${held.docUuid} v${held.version} unknown")
        return Instant.MAX
    }

    private fun raise(
        tenant: TenantId,
        held: HeldVersion,
        code: String,
    ) {
        raising.raise(tenant, held.docUuid, held.version, held.entityRef, held.periodRef, listOf(code))
    }

    private fun release(
        tenant: TenantId,
        held: HeldVersion,
        lane: Lane,
    ): Boolean {
        if (store.releaseHeld(tenant, held.docUuid, held.version) != 1) return false
        queue.enqueue(held.docUuid, held.version, lane)
        return true
    }

    companion object {
        /** The most held versions one sweep takes per tenant. */
        const val BATCH = 100

        /** The interval between two sweeps; the one constant the scheduler registration reads. */
        val INTERVAL: Duration = Duration.ofSeconds(60)

        private val LOG = System.getLogger(HeldCreditNoteSweeper::class.java.name)
    }
}
